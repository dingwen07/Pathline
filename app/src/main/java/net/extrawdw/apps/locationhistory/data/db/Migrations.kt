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
     *  - FTS5 virtual tables + sync triggers over places and tags, backfilled from existing rows.
     *
     * Table/index DDL mirrors what Room generates for the v2 entities (see `app/schemas/2.json`) so the
     * post-migration identity check passes; the FTS objects are extra and Room ignores them.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `places` ADD COLUMN `types` TEXT")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `tags` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`canonicalName` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
                    "`createdAtMs` INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_canonicalName` ON `tags` (`canonicalName`)",
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `entity_tags` (" +
                    "`tagId` INTEGER NOT NULL, `targetType` TEXT NOT NULL, `targetId` INTEGER NOT NULL, " +
                    "`createdAtMs` INTEGER NOT NULL, PRIMARY KEY(`tagId`, `targetType`, `targetId`))",
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
                    "`updatedAtMs` INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_annotations_targetType_targetId_kind` " +
                    "ON `annotations` (`targetType`, `targetId`, `kind`)",
            )

            AppDatabase.FTS_CREATE.forEach(db::execSQL)
            AppDatabase.FTS_BACKFILL.forEach(db::execSQL)
        }
    }

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
    )
}
