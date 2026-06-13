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

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
    )
}
