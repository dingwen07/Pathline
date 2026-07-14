package net.extrawdw.apps.locationhistory.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `places` ADD COLUMN `types` TEXT")

            db.execSQL("DROP TABLE IF EXISTS `state_training_examples`")
            db.execSQL("DROP TABLE IF EXISTS `transport_training_examples`")

            // IMU/barometer evidence channels (recorder/timeline refactor, June 2026).
            db.execSQL("ALTER TABLE `location_samples` ADD COLUMN `motionVariance` REAL")
            db.execSQL("ALTER TABLE `location_samples` ADD COLUMN `stepCadenceHz` REAL")
            db.execSQL("ALTER TABLE `location_samples` ADD COLUMN `gravityAngleDeltaDeg` REAL")
            db.execSQL("ALTER TABLE `location_samples` ADD COLUMN `pressureHpa` REAL")
            // Step-counter delta, stamped per delivered batch (see StepCounterMonitor).
            db.execSQL("ALTER TABLE `location_samples` ADD COLUMN `stepDelta` INTEGER")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `tags` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`canonicalName` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
                        "`createdAtMs` INTEGER NOT NULL, `createdBy` TEXT)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_canonicalName` ON `tags` (`canonicalName`)",
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `entity_tags` (" +
                        "`tagId` INTEGER NOT NULL, `targetType` TEXT NOT NULL, `targetId` INTEGER NOT NULL, " +
                        "`createdAtMs` INTEGER NOT NULL, `createdBy` TEXT, " +
                        "PRIMARY KEY(`tagId`, `targetType`, `targetId`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_entity_tags_tagId` ON `entity_tags` (`tagId`)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_entity_tags_targetType_targetId` " +
                        "ON `entity_tags` (`targetType`, `targetId`)",
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `annotations` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `targetType` TEXT NOT NULL, " +
                        "`targetId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `content` TEXT NOT NULL, " +
                        "`updatedAtMs` INTEGER NOT NULL, `updatedBy` TEXT)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_annotations_targetType_targetId_kind` " +
                        "ON `annotations` (`targetType`, `targetId`, `kind`)",
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `concepts` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`canonicalName` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
                        "`kind` TEXT, `description` TEXT, " +
                        "`createdAtMs` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL, " +
                        "`createdBy` TEXT, `updatedBy` TEXT, " +
                        "`archivedAtMs` INTEGER, `archivedBy` TEXT)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_concepts_canonicalName` ON `concepts` (`canonicalName`)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_concepts_kind` ON `concepts` (`kind`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `concept_members` (" +
                        "`conceptId` INTEGER NOT NULL, `targetType` TEXT NOT NULL, `targetId` INTEGER NOT NULL, " +
                        "`createdAtMs` INTEGER NOT NULL, `createdBy` TEXT, " +
                        "PRIMARY KEY(`conceptId`, `targetType`, `targetId`))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_concept_members_conceptId` ON `concept_members` (`conceptId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_concept_members_targetType_targetId` " +
                        "ON `concept_members` (`targetType`, `targetId`)",
            )

            AppDatabase.FTS_CREATE.forEach(db::execSQL)
            AppDatabase.FTS_BACKFILL.forEach(db::execSQL)
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
     * Legacy candidate suggestions are intentionally cleared: their request/result frame was not
     * recorded, so promoting them would be an unsafe guess.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `places` ADD COLUMN `coordinateState` TEXT NOT NULL " +
                        "DEFAULT 'UNKNOWN'",
            )

            db.execSQL(
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
            db.execSQL(
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

            db.execSQL(
                "INSERT INTO `place_coordinate_repairs` $journalColumns SELECT " +
                        "$unchangedValues, 'WGS84_CANONICAL', `source`, " +
                        "'AUTO_OUTSIDE_MAINLAND_IDENTITY', '$historicalProfile', $nowMs, NULL " +
                        "FROM `places` WHERE `coordinateState` = 'UNKNOWN' AND $safeOutside",
            )
            db.execSQL(
                "UPDATE `places` SET `coordinateState` = 'WGS84_CANONICAL' " +
                        "WHERE `coordinateState` = 'UNKNOWN' AND $safeOutside",
            )

            val directGoogle = "`coordinateState` = 'UNKNOWN' AND `source` = 'MAPS' " +
                    "AND `anchorLatitude` IS NOT NULL AND `anchorLongitude` IS NOT NULL " +
                    "AND `latitude` = `anchorLatitude` AND `longitude` = `anchorLongitude`"
            db.execSQL(
                "INSERT INTO `place_coordinate_repairs` $journalColumns SELECT " +
                        "$unchangedValues, 'LEGACY_GOOGLE_MAP_CENTER_AND_BASELINE', " +
                        "`source`, 'AUTO_CLASSIFIED_GOOGLE_PROVIDER_BASELINE', '$historicalProfile', " +
                        "$nowMs, NULL FROM `places` WHERE $directGoogle",
            )
            db.execSQL(
                "UPDATE `places` SET `coordinateState` = " +
                        "'LEGACY_GOOGLE_MAP_CENTER_AND_BASELINE' " +
                        "WHERE $directGoogle",
            )

            db.execSQL(
                "ALTER TABLE `visits` ADD COLUMN `candidateCoordinateFrame` TEXT NOT NULL " +
                        "DEFAULT 'UNKNOWN'",
            )
            db.execSQL(
                "ALTER TABLE `visits` ADD COLUMN `candidateOrigin` TEXT NOT NULL " +
                        "DEFAULT 'UNKNOWN'",
            )
            db.execSQL(
                "UPDATE `visits` SET `candidateName` = NULL, " +
                        "`candidateGooglePlaceId` = NULL, `candidateLatitude` = NULL, " +
                        "`candidateLongitude` = NULL, `candidateCoordinateFrame` = 'UNKNOWN', " +
                        "`candidateOrigin` = 'UNKNOWN'",
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
    )
}
