package net.extrawdw.apps.locationhistory.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        LocationSampleEntity::class,
        PlaceEntity::class,
        VisitEntity::class,
        TripEntity::class,
        GeofenceEntity::class,
        StateTrainingExampleEntity::class,
        TransportTrainingExampleEntity::class,
        BackupDirtyPartitionEntity::class,
        TagEntity::class,
        EntityTagEntity::class,
        AnnotationEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun locationSampleDao(): LocationSampleDao
    abstract fun placeDao(): PlaceDao
    abstract fun visitDao(): VisitDao
    abstract fun tripDao(): TripDao
    abstract fun trainingDao(): TrainingDao
    abstract fun geofenceDao(): GeofenceDao
    abstract fun backupDao(): BackupDao
    abstract fun tagDao(): TagDao
    abstract fun annotationDao(): AnnotationDao
    abstract fun searchDao(): SearchDao

    companion object {
        const val NAME = "pathline.db"

        /** Must equal the `version` above; used by the backup engine for restore compatibility. */
        const val SCHEMA_VERSION = 2

        /**
         * SQL that creates the backup dirty-partition triggers. Run from a
         * `RoomDatabase.Callback.onCreate` because Room creates tables from entities but never
         * triggers.
         *
         * Design notes:
         *  - The week bucket is `((dayEpoch + 3) / 7) * 7 - 3` — the Monday-aligned `dayEpoch`,
         *    matching `TimeBuckets.weekStartDayEpoch`.
         *  - UPDATE triggers mark both OLD and NEW weeks because an edit can move a row across a
         *    week boundary.
         *  - Each mark is an `INSERT ... SELECT ... WHERE NOT EXISTS` rather than `INSERT OR IGNORE`.
         *    SQLite makes the *triggering* statement's conflict policy win inside a trigger body, and
         *    Room's `@Insert` emits `INSERT OR ABORT`, which would override `OR IGNORE` and abort the
         *    whole insert on a duplicate. `WHERE NOT EXISTS` never hits the unique constraint, so it
         *    is immune to the outer conflict policy.
         */
        val DIRTY_TRIGGERS: List<String> = buildList {
            fun mark(stream: String, dayExpr: String): String {
                val w = "(($dayExpr + 3) / 7) * 7 - 3"
                return "INSERT INTO backup_dirty_partitions(stream, weekStart) " +
                        "SELECT '$stream', $w WHERE NOT EXISTS " +
                        "(SELECT 1 FROM backup_dirty_partitions WHERE stream = '$stream' AND weekStart = $w);"
            }

            // location_samples
            add(
                "CREATE TRIGGER IF NOT EXISTS trg_dirty_samples_ai AFTER INSERT ON location_samples BEGIN ${
                    mark(
                        "samples",
                        "NEW.dayEpoch"
                    )
                } END;"
            )
            add(
                "CREATE TRIGGER IF NOT EXISTS trg_dirty_samples_au AFTER UPDATE ON location_samples BEGIN ${
                    mark(
                        "samples",
                        "NEW.dayEpoch"
                    )
                } END;"
            )
            add(
                "CREATE TRIGGER IF NOT EXISTS trg_dirty_samples_ad AFTER DELETE ON location_samples BEGIN ${
                    mark(
                        "samples",
                        "OLD.dayEpoch"
                    )
                } END;"
            )

            // visits
            add(
                "CREATE TRIGGER IF NOT EXISTS trg_dirty_visits_ai AFTER INSERT ON visits BEGIN ${
                    mark(
                        "visits",
                        "NEW.dayEpoch"
                    )
                } END;"
            )
            add(
                "CREATE TRIGGER IF NOT EXISTS trg_dirty_visits_au AFTER UPDATE ON visits BEGIN ${
                    mark(
                        "visits",
                        "OLD.dayEpoch"
                    )
                } ${mark("visits", "NEW.dayEpoch")} END;"
            )
            add(
                "CREATE TRIGGER IF NOT EXISTS trg_dirty_visits_ad AFTER DELETE ON visits BEGIN ${
                    mark(
                        "visits",
                        "OLD.dayEpoch"
                    )
                } END;"
            )

            // trips
            add(
                "CREATE TRIGGER IF NOT EXISTS trg_dirty_trips_ai AFTER INSERT ON trips BEGIN ${
                    mark(
                        "trips",
                        "NEW.dayEpoch"
                    )
                } END;"
            )
            add(
                "CREATE TRIGGER IF NOT EXISTS trg_dirty_trips_au AFTER UPDATE ON trips BEGIN ${
                    mark(
                        "trips",
                        "OLD.dayEpoch"
                    )
                } ${mark("trips", "NEW.dayEpoch")} END;"
            )
            add(
                "CREATE TRIGGER IF NOT EXISTS trg_dirty_trips_ad AFTER DELETE ON trips BEGIN ${
                    mark(
                        "trips",
                        "OLD.dayEpoch"
                    )
                } END;"
            )
        }

        /**
         * FTS5 virtual tables + content-sync triggers for full-text search over places and tags.
         * SQLCipher 4.6.1 ships fts5 compiled in (verified), so this works on the current stable Room
         * 2.8.4 (which has only `@Fts4`) via raw SQL; queried later through `@RawQuery`. A move to room3
         * `@Fts5` entities is a deferred, internal-only refactor that needs no API change.
         *
         * External-content tables (`content='<table>'`, `content_rowid='id'`): the index references
         * the base row by rowid and is kept in sync by the triggers. Run from
         * [net.extrawdw.apps.locationhistory.di.DatabaseModule]'s `onCreate` (fresh install) and from
         * `Migration(1,2)` (upgrade); the migration additionally runs [FTS_BACKFILL] for existing rows.
         */
        val FTS_CREATE: List<String> = listOf(
            "CREATE VIRTUAL TABLE IF NOT EXISTS places_fts USING fts5(" +
                "name, address, category, types, content='places', content_rowid='id')",
            "CREATE TRIGGER IF NOT EXISTS trg_places_fts_ai AFTER INSERT ON places BEGIN " +
                "INSERT INTO places_fts(rowid, name, address, category, types) " +
                "VALUES (new.id, new.name, new.address, new.category, new.types); END;",
            "CREATE TRIGGER IF NOT EXISTS trg_places_fts_ad AFTER DELETE ON places BEGIN " +
                "INSERT INTO places_fts(places_fts, rowid, name, address, category, types) " +
                "VALUES('delete', old.id, old.name, old.address, old.category, old.types); END;",
            "CREATE TRIGGER IF NOT EXISTS trg_places_fts_au AFTER UPDATE ON places BEGIN " +
                "INSERT INTO places_fts(places_fts, rowid, name, address, category, types) " +
                "VALUES('delete', old.id, old.name, old.address, old.category, old.types); " +
                "INSERT INTO places_fts(rowid, name, address, category, types) " +
                "VALUES (new.id, new.name, new.address, new.category, new.types); END;",
            "CREATE VIRTUAL TABLE IF NOT EXISTS tags_fts USING fts5(" +
                "displayName, content='tags', content_rowid='id')",
            "CREATE TRIGGER IF NOT EXISTS trg_tags_fts_ai AFTER INSERT ON tags BEGIN " +
                "INSERT INTO tags_fts(rowid, displayName) VALUES (new.id, new.displayName); END;",
            "CREATE TRIGGER IF NOT EXISTS trg_tags_fts_ad AFTER DELETE ON tags BEGIN " +
                "INSERT INTO tags_fts(tags_fts, rowid, displayName) " +
                "VALUES('delete', old.id, old.displayName); END;",
            "CREATE TRIGGER IF NOT EXISTS trg_tags_fts_au AFTER UPDATE ON tags BEGIN " +
                "INSERT INTO tags_fts(tags_fts, rowid, displayName) " +
                "VALUES('delete', old.id, old.displayName); " +
                "INSERT INTO tags_fts(rowid, displayName) VALUES (new.id, new.displayName); END;",
        )

        /** Populate the FTS indexes from rows that already exist (upgrade path only). */
        val FTS_BACKFILL: List<String> = listOf(
            "INSERT INTO places_fts(rowid, name, address, category, types) " +
                "SELECT id, name, address, category, types FROM places",
            "INSERT INTO tags_fts(rowid, displayName) SELECT id, displayName FROM tags",
        )
    }
}
