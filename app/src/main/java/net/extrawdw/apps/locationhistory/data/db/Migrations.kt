package net.extrawdw.apps.locationhistory.data.db

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * On-device Room migrations.
 *
 * From the first public release on, **every** schema change must:
 *  1. bump `AppDatabase.version` and the mirrored `AppDatabase.SCHEMA_VERSION`,
 *  2. add a `Migration(from, to)` to [ALL], and
 *  3. commit the new `app/schemas/<version>.json` (auto-exported).
 *
 * A missing upgrade migration **fails fast** (Room throws on open) instead of silently wiping user
 * data — the destructive *upgrade* fallback was removed for exactly this reason (see
 * `DatabaseModule`). Only a version *downgrade* still rebuilds rather than crash.
 */
object AppMigrations {

    /**
     * v1 -> v2: the data-API "tags, notes, memories & search" schema, landed in a single bump.
     *  - `places.types` — the full comma-joined Google place-type list ([category] is primary).
     *  - `tags` + polymorphic `entity_tags` join.
     *  - `annotations` (notes + memories), one of each kind per target.
     *  - `concepts` + polymorphic `concept_members` join (first-class semantic groups), including
     *    the archive columns (`archivedAtMs`/`archivedBy`, null = active) and CONCEPT members
     *    (nesting; cycles rejected in `ConceptStore`).
     *  - Writer attribution columns (`createdBy`/`updatedBy`, null = Pathline itself).
     *  - FTS5 virtual tables + sync triggers over places, tags and concepts, backfilled from
     *    existing rows.
     *
     * Also part of the v2 bump (recorder/timeline refactor, June 2026): the on-device ML training
     * pipeline was deleted, so the v1 `state_training_examples` / `transport_training_examples`
     * tables are dropped — they are no longer Room entities, and leaving them would orphan stale
     * personal data in the encrypted DB forever.
     *
     * Pre-release, v2 is **edited in place** rather than bumped (production devices are all v1; the
     * only v2 installs are dev devices, refreshed via clear-data + backup restore). Table/index DDL
     * mirrors what Room generates for the v2 entities (see `app/schemas/2.json`) so the
     * post-migration identity check passes; the FTS objects are extra and Room ignores them.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `places` ADD COLUMN `types` TEXT")

            connection.execSQL("DROP TABLE IF EXISTS `state_training_examples`")
            connection.execSQL("DROP TABLE IF EXISTS `transport_training_examples`")

            // IMU/barometer evidence channels (recorder/timeline refactor, June 2026).
            connection.execSQL("ALTER TABLE `location_samples` ADD COLUMN `motionVariance` REAL")
            connection.execSQL("ALTER TABLE `location_samples` ADD COLUMN `stepCadenceHz` REAL")
            connection.execSQL("ALTER TABLE `location_samples` ADD COLUMN `gravityAngleDeltaDeg` REAL")
            connection.execSQL("ALTER TABLE `location_samples` ADD COLUMN `pressureHpa` REAL")
            // Step-counter delta, stamped per delivered batch (see StepCounterMonitor).
            connection.execSQL("ALTER TABLE `location_samples` ADD COLUMN `stepDelta` INTEGER")

            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `tags` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`canonicalName` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
                        "`createdAtMs` INTEGER NOT NULL, `createdBy` TEXT)",
            )
            connection.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_canonicalName` ON `tags` (`canonicalName`)",
            )

            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `entity_tags` (" +
                        "`tagId` INTEGER NOT NULL, `targetType` TEXT NOT NULL, `targetId` INTEGER NOT NULL, " +
                        "`createdAtMs` INTEGER NOT NULL, `createdBy` TEXT, " +
                        "PRIMARY KEY(`tagId`, `targetType`, `targetId`))",
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_entity_tags_tagId` ON `entity_tags` (`tagId`)")
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_entity_tags_targetType_targetId` " +
                        "ON `entity_tags` (`targetType`, `targetId`)",
            )

            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `annotations` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `targetType` TEXT NOT NULL, " +
                        "`targetId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `content` TEXT NOT NULL, " +
                        "`updatedAtMs` INTEGER NOT NULL, `updatedBy` TEXT)",
            )
            connection.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_annotations_targetType_targetId_kind` " +
                        "ON `annotations` (`targetType`, `targetId`, `kind`)",
            )

            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `concepts` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`canonicalName` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
                        "`kind` TEXT, `description` TEXT, " +
                        "`createdAtMs` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL, " +
                        "`createdBy` TEXT, `updatedBy` TEXT, " +
                        "`archivedAtMs` INTEGER, `archivedBy` TEXT)",
            )
            connection.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_concepts_canonicalName` ON `concepts` (`canonicalName`)",
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_concepts_kind` ON `concepts` (`kind`)")

            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `concept_members` (" +
                        "`conceptId` INTEGER NOT NULL, `targetType` TEXT NOT NULL, `targetId` INTEGER NOT NULL, " +
                        "`createdAtMs` INTEGER NOT NULL, `createdBy` TEXT, " +
                        "PRIMARY KEY(`conceptId`, `targetType`, `targetId`))",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_concept_members_conceptId` ON `concept_members` (`conceptId`)",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_concept_members_targetType_targetId` " +
                        "ON `concept_members` (`targetType`, `targetId`)",
            )

            LEGACY_FTS_CREATE.forEach(connection::execSQL)
            LEGACY_FTS_BACKFILL.forEach(connection::execSQL)
        }
    }

    /**
     * v2 -> v3: coordinate-boundary provenance for mainland Google Maps/Places compatibility.
     *
     * No recorded or place geometry is rewritten. Rows safely beyond the mainland mask's expanded
     * global bounds are exact identity under the frozen historical hypothesis and can therefore be
     * marked canonical without changing a double. The before/after state is journaled. The other
     * narrow classification is an untouched MAPS row whose complete baseline is bit-for-bit its
     * center; it remains provider-frame legacy and is not normalized. Differing/mixed MAPS rows and
     * all rows whose provenance is ambiguous stay UNKNOWN.
     * Complete Google candidates safely beyond the mainland transform envelope are retained as
     * canonical because both historical frame interpretations are exact identity there. Every other
     * legacy candidate is cleared because its request/result frame was not recorded.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "ALTER TABLE `places` ADD COLUMN `coordinateState` TEXT NOT NULL " +
                        "DEFAULT 'UNKNOWN'",
            )

            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `place_coordinate_repairs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`placeId` INTEGER NOT NULL, " +
                        "`originalLatitude` REAL NOT NULL, " +
                        "`originalLongitude` REAL NOT NULL, " +
                        "`originalRadiusMeters` REAL NOT NULL, " +
                        "`originalAnchorLatitude` REAL, " +
                        "`originalAnchorLongitude` REAL, " +
                        "`originalAnchorRadiusMeters` REAL, " +
                        "`originalCoordinateState` TEXT NOT NULL, " +
                        "`originalSource` TEXT NOT NULL, " +
                        "`repairedLatitude` REAL NOT NULL, " +
                        "`repairedLongitude` REAL NOT NULL, " +
                        "`repairedRadiusMeters` REAL NOT NULL, " +
                        "`repairedAnchorLatitude` REAL, " +
                        "`repairedAnchorLongitude` REAL, " +
                        "`repairedAnchorRadiusMeters` REAL, " +
                        "`repairedCoordinateState` TEXT NOT NULL, " +
                        "`repairedSource` TEXT NOT NULL, " +
                        "`decision` TEXT NOT NULL, " +
                        "`profileId` TEXT, " +
                        "`repairedAtMs` INTEGER NOT NULL, " +
                        "`undoneAtMs` INTEGER)",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_place_coordinate_repairs_placeId` " +
                        "ON `place_coordinate_repairs` (`placeId`)",
            )

            // The committed mainland geometry spans [18.1720757, 53.570963401] latitude and
            // [73.606083281, 134.740415954] longitude. The transform's inverse edge guard is
            // 0.02 degrees, so points beyond this expanded box are provably on the exact identity
            // path without loading or approximating the polygon in SQLite.
            val centerValid = "`latitude` BETWEEN -90.0 AND 90.0 AND " +
                    "`longitude` BETWEEN -180.0 AND 180.0"
            val centerOutside = "(`latitude` < 18.1520757 OR `latitude` > 53.590963401 " +
                    "OR `longitude` < 73.586083281 OR `longitude` > 134.760415954)"
            val anchorAbsent = "`anchorLatitude` IS NULL AND `anchorLongitude` IS NULL"
            val anchorValidOutside = "(`anchorLatitude` IS NOT NULL AND " +
                    "`anchorLongitude` IS NOT NULL AND `anchorLatitude` BETWEEN -90.0 AND 90.0 " +
                    "AND `anchorLongitude` BETWEEN -180.0 AND 180.0 AND " +
                    "(`anchorLatitude` < 18.1520757 OR `anchorLatitude` > 53.590963401 " +
                    "OR `anchorLongitude` < 73.586083281 " +
                    "OR `anchorLongitude` > 134.760415954))"
            val safeOutside = "$centerValid AND $centerOutside AND " +
                    "($anchorAbsent OR $anchorValidOutside)"
            val journalColumns =
                "(`placeId`, `originalLatitude`, `originalLongitude`, " +
                        "`originalRadiusMeters`, `originalAnchorLatitude`, " +
                        "`originalAnchorLongitude`, `originalAnchorRadiusMeters`, " +
                        "`originalCoordinateState`, `originalSource`, `repairedLatitude`, `repairedLongitude`, " +
                        "`repairedRadiusMeters`, `repairedAnchorLatitude`, " +
                        "`repairedAnchorLongitude`, `repairedAnchorRadiusMeters`, " +
                        "`repairedCoordinateState`, `repairedSource`, `decision`, `profileId`, `repairedAtMs`, " +
                        "`undoneAtMs`)"
            val unchangedValues =
                "`id`, `latitude`, `longitude`, `radiusMeters`, `anchorLatitude`, " +
                        "`anchorLongitude`, `anchorRadiusMeters`, 'UNKNOWN', `source`, `latitude`, " +
                        "`longitude`, `radiusMeters`, `anchorLatitude`, `anchorLongitude`, " +
                        "`anchorRadiusMeters`"
            val historicalProfile =
                "historical-google-android-places-5.2.0-mainland-2026-07"
            val nowMs = "(CAST(strftime('%s','now') AS INTEGER) * 1000)"

            connection.execSQL(
                "INSERT INTO `place_coordinate_repairs` $journalColumns SELECT " +
                        "$unchangedValues, 'WGS84_CANONICAL', `source`, " +
                        "'AUTO_OUTSIDE_MAINLAND_IDENTITY', '$historicalProfile', $nowMs, NULL " +
                        "FROM `places` WHERE `coordinateState` = 'UNKNOWN' AND $safeOutside",
            )
            connection.execSQL(
                "UPDATE `places` SET `coordinateState` = 'WGS84_CANONICAL' " +
                        "WHERE `coordinateState` = 'UNKNOWN' AND $safeOutside",
            )

            val directGoogle = "`coordinateState` = 'UNKNOWN' AND `source` = 'MAPS' " +
                    "AND `anchorLatitude` IS NOT NULL AND `anchorLongitude` IS NOT NULL " +
                    "AND `latitude` = `anchorLatitude` AND `longitude` = `anchorLongitude`"
            connection.execSQL(
                "INSERT INTO `place_coordinate_repairs` $journalColumns SELECT " +
                        "$unchangedValues, 'LEGACY_GOOGLE_MAP_CENTER_AND_BASELINE', " +
                        "`source`, 'AUTO_CLASSIFIED_GOOGLE_PROVIDER_BASELINE', '$historicalProfile', " +
                        "$nowMs, NULL FROM `places` WHERE $directGoogle",
            )
            connection.execSQL(
                "UPDATE `places` SET `coordinateState` = " +
                        "'LEGACY_GOOGLE_MAP_CENTER_AND_BASELINE' " +
                        "WHERE $directGoogle",
            )

            connection.execSQL(
                "ALTER TABLE `visits` ADD COLUMN `candidateCoordinateFrame` TEXT NOT NULL " +
                        "DEFAULT 'UNKNOWN'",
            )
            connection.execSQL(
                "ALTER TABLE `visits` ADD COLUMN `candidateOrigin` TEXT NOT NULL " +
                        "DEFAULT 'UNKNOWN'",
            )
            val safeIdentityCandidate =
                "`candidateName` IS NOT NULL AND `candidateGooglePlaceId` IS NOT NULL AND " +
                        "`candidateLatitude` IS NOT NULL AND `candidateLongitude` IS NOT NULL AND " +
                        "`candidateLatitude` BETWEEN -90.0 AND 90.0 AND " +
                        "`candidateLongitude` BETWEEN -180.0 AND 180.0 AND " +
                        "(`candidateLatitude` < 18.1520757 OR " +
                        "`candidateLatitude` > 53.590963401 OR " +
                        "`candidateLongitude` < 73.586083281 OR " +
                        "`candidateLongitude` > 134.760415954)"
            connection.execSQL(
                "UPDATE `visits` SET `candidateCoordinateFrame` = 'WGS84', " +
                        "`candidateOrigin` = 'MAPS' WHERE $safeIdentityCandidate",
            )
            connection.execSQL(
                "UPDATE `visits` SET `candidateName` = NULL, " +
                        "`candidateGooglePlaceId` = NULL, `candidateLatitude` = NULL, " +
                        "`candidateLongitude` = NULL, `candidateCoordinateFrame` = 'UNKNOWN', " +
                        "`candidateOrigin` = 'UNKNOWN' WHERE NOT ($safeIdentityCandidate)",
            )
        }
    }

    /**
     * v3 -> v4: transfer the three external-content FTS5 indexes from hand-written SQL/triggers to
     * Room 3 `@Fts5` entities. Room only removes triggers with its own
     * `room_fts_content_sync_` prefix, so the nine legacy `trg_*_fts_*` triggers must be dropped
     * explicitly before Room installs its managed trigger set.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override suspend fun migrate(connection: SQLiteConnection) {
            LEGACY_FTS_TRIGGER_NAMES.forEach { name ->
                connection.execSQL("DROP TRIGGER IF EXISTS `$name`")
            }

            FTS_TABLE_NAMES.forEach { name ->
                connection.execSQL("DROP TABLE IF EXISTS `$name`")
            }

            ROOM3_FTS_CREATE.forEach(connection::execSQL)
            FTS_REBUILD.forEach(connection::execSQL)
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE visits ADD COLUMN stopMerge INTEGER NOT NULL DEFAULT 0")
            connection.execSQL("ALTER TABLE trips ADD COLUMN stopMerge INTEGER NOT NULL DEFAULT 0")
            connection.execSQL("CREATE TABLE IF NOT EXISTS deleted_time_ranges (startMs INTEGER NOT NULL PRIMARY KEY, endMs INTEGER NOT NULL)")
        }
    }

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
    )

    /**
     * Migration-only copy of the FTS5 objects created by the historical v1 -> v2 migration.
     * Keep these separate from the Room-managed v4 schema: a v1 database must still traverse the
     * exact public 1 -> 2 -> 3 -> 4 chain without teaching fresh databases about legacy triggers.
     */
    private val LEGACY_FTS_CREATE = listOf(
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
        "CREATE VIRTUAL TABLE IF NOT EXISTS concepts_fts USING fts5(" +
                "displayName, kind, description, content='concepts', content_rowid='id')",
        "CREATE TRIGGER IF NOT EXISTS trg_concepts_fts_ai AFTER INSERT ON concepts BEGIN " +
                "INSERT INTO concepts_fts(rowid, displayName, kind, description) " +
                "VALUES (new.id, new.displayName, new.kind, new.description); END;",
        "CREATE TRIGGER IF NOT EXISTS trg_concepts_fts_ad AFTER DELETE ON concepts BEGIN " +
                "INSERT INTO concepts_fts(concepts_fts, rowid, displayName, kind, description) " +
                "VALUES('delete', old.id, old.displayName, old.kind, old.description); END;",
        "CREATE TRIGGER IF NOT EXISTS trg_concepts_fts_au AFTER UPDATE ON concepts BEGIN " +
                "INSERT INTO concepts_fts(concepts_fts, rowid, displayName, kind, description) " +
                "VALUES('delete', old.id, old.displayName, old.kind, old.description); " +
                "INSERT INTO concepts_fts(rowid, displayName, kind, description) " +
                "VALUES (new.id, new.displayName, new.kind, new.description); END;",
    )

    private val LEGACY_FTS_BACKFILL = listOf(
        "INSERT INTO places_fts(rowid, name, address, category, types) " +
                "SELECT id, name, address, category, types FROM places",
        "INSERT INTO tags_fts(rowid, displayName) SELECT id, displayName FROM tags",
        "INSERT INTO concepts_fts(rowid, displayName, kind, description) " +
                "SELECT id, displayName, kind, description FROM concepts",
    )

    private val LEGACY_FTS_TRIGGER_NAMES = listOf(
        "trg_places_fts_ai",
        "trg_places_fts_ad",
        "trg_places_fts_au",
        "trg_tags_fts_ai",
        "trg_tags_fts_ad",
        "trg_tags_fts_au",
        "trg_concepts_fts_ai",
        "trg_concepts_fts_ad",
        "trg_concepts_fts_au",
    )

    private val FTS_TABLE_NAMES = listOf("places_fts", "tags_fts", "concepts_fts")

    /** DDL must match Room 3's exported v4 schema exactly, including the explicit tokenizer. */
    private val ROOM3_FTS_CREATE = listOf(
        "CREATE VIRTUAL TABLE IF NOT EXISTS `places_fts` USING FTS5(" +
                "`name`, `address`, `category`, `types`, tokenize=`unicode61`, " +
                "content=`places`, content_rowid=`id`)",
        "CREATE VIRTUAL TABLE IF NOT EXISTS `tags_fts` USING FTS5(" +
                "`displayName`, tokenize=`unicode61`, content=`tags`, content_rowid=`id`)",
        "CREATE VIRTUAL TABLE IF NOT EXISTS `concepts_fts` USING FTS5(" +
                "`displayName`, `kind`, `description`, tokenize=`unicode61`, " +
                "content=`concepts`, content_rowid=`id`)",
    )

    /** Rebuild from the external content tables; creating future sync triggers is not a backfill. */
    private val FTS_REBUILD = FTS_TABLE_NAMES.map { name ->
        "INSERT INTO `$name`(`$name`) VALUES('rebuild')"
    }
}
