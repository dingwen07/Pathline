package net.extrawdw.apps.locationhistory.data.db

import androidx.room.migration.Migration

/**
 * On-device Room migrations. **Empty today** because the released baseline is schema v1, so fresh
 * installs create v1 directly and there is nothing to migrate yet.
 *
 * From the first public release on, **every** schema change must:
 *  1. bump `AppDatabase.version` and the mirrored `AppDatabase.SCHEMA_VERSION`,
 *  2. add a `Migration(from, to)` to [ALL], and
 *  3. commit the new `app/schemas/<version>.json` (auto-exported).
 *
 * A missing upgrade migration now **fails fast** (Room throws on open) instead of silently wiping
 * user data — the destructive *upgrade* fallback was removed for exactly this reason (see
 * `DatabaseModule`). Only a version *downgrade* still rebuilds rather than crash.
 */
object AppMigrations {
    val ALL: Array<Migration> = arrayOf(
        // MIGRATION_1_2, MIGRATION_2_3, ... go here once the schema moves past v1.
    )
}
