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
    ],
    version = 6,
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

    companion object {
        const val NAME = "pathline.db"

        /** Must equal the `version` above; used by the backup engine for restore compatibility. */
        const val SCHEMA_VERSION = 6

        /**
         * SQL that creates the backup dirty-partition triggers. Run from the migrations (for
         * existing installs) and a `RoomDatabase.Callback.onCreate` (for fresh installs, since Room
         * creates tables from entities but never triggers).
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
            add("CREATE TRIGGER IF NOT EXISTS trg_dirty_samples_ai AFTER INSERT ON location_samples BEGIN ${mark("samples", "NEW.dayEpoch")} END;")
            add("CREATE TRIGGER IF NOT EXISTS trg_dirty_samples_au AFTER UPDATE ON location_samples BEGIN ${mark("samples", "NEW.dayEpoch")} END;")
            add("CREATE TRIGGER IF NOT EXISTS trg_dirty_samples_ad AFTER DELETE ON location_samples BEGIN ${mark("samples", "OLD.dayEpoch")} END;")

            // visits
            add("CREATE TRIGGER IF NOT EXISTS trg_dirty_visits_ai AFTER INSERT ON visits BEGIN ${mark("visits", "NEW.dayEpoch")} END;")
            add("CREATE TRIGGER IF NOT EXISTS trg_dirty_visits_au AFTER UPDATE ON visits BEGIN ${mark("visits", "OLD.dayEpoch")} ${mark("visits", "NEW.dayEpoch")} END;")
            add("CREATE TRIGGER IF NOT EXISTS trg_dirty_visits_ad AFTER DELETE ON visits BEGIN ${mark("visits", "OLD.dayEpoch")} END;")

            // trips
            add("CREATE TRIGGER IF NOT EXISTS trg_dirty_trips_ai AFTER INSERT ON trips BEGIN ${mark("trips", "NEW.dayEpoch")} END;")
            add("CREATE TRIGGER IF NOT EXISTS trg_dirty_trips_au AFTER UPDATE ON trips BEGIN ${mark("trips", "OLD.dayEpoch")} ${mark("trips", "NEW.dayEpoch")} END;")
            add("CREATE TRIGGER IF NOT EXISTS trg_dirty_trips_ad AFTER DELETE ON trips BEGIN ${mark("trips", "OLD.dayEpoch")} END;")
        }

        /** Names of every dirty-tracker trigger, so a migration can drop + recreate them. */
        val DIRTY_TRIGGER_NAMES: List<String> = listOf(
            "trg_dirty_samples_ai", "trg_dirty_samples_au", "trg_dirty_samples_ad",
            "trg_dirty_visits_ai", "trg_dirty_visits_au", "trg_dirty_visits_ad",
            "trg_dirty_trips_ai", "trg_dirty_trips_au", "trg_dirty_trips_ad",
            // Legacy segment triggers (pre-v6); kept here so the migration drops them.
            "trg_dirty_segments_ai", "trg_dirty_segments_au", "trg_dirty_segments_ad",
        )

        /** Seeds the dirty set with every week that already has data (run once on upgrade). */
        val DIRTY_SEED: List<String> = listOf(
            "INSERT OR IGNORE INTO backup_dirty_partitions(stream, weekStart) SELECT 'samples', ((dayEpoch + 3) / 7) * 7 - 3 FROM location_samples;",
            "INSERT OR IGNORE INTO backup_dirty_partitions(stream, weekStart) SELECT 'visits', ((dayEpoch + 3) / 7) * 7 - 3 FROM visits;",
            "INSERT OR IGNORE INTO backup_dirty_partitions(stream, weekStart) SELECT 'trips', ((dayEpoch + 3) / 7) * 7 - 3 FROM trips;",
        )
    }
}
