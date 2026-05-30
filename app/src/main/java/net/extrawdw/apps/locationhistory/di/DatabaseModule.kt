package net.extrawdw.apps.locationhistory.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.extrawdw.apps.locationhistory.data.db.AppDatabase
import net.extrawdw.apps.locationhistory.data.db.BackupDao
import net.extrawdw.apps.locationhistory.data.db.GeofenceDao
import net.extrawdw.apps.locationhistory.data.db.LocationSampleDao
import net.extrawdw.apps.locationhistory.data.db.PlaceDao
import net.extrawdw.apps.locationhistory.data.db.TrainingDao
import net.extrawdw.apps.locationhistory.data.db.TripDao
import net.extrawdw.apps.locationhistory.data.db.VisitDao
import net.extrawdw.apps.locationhistory.security.DatabaseKeyStore
import net.extrawdw.apps.locationhistory.security.SqlCipherSupport
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        keyStore: DatabaseKeyStore,
    ): AppDatabase {
        // The net.zetetic:sqlcipher-android artifact does not auto-load its native library; it must
        // be loaded before any SQLCipher use (the migration helper and the open-helper factory).
        System.loadLibrary("sqlcipher")
        val rawKey = keyStore.databasePassphrase()
        // Transparently upgrade any pre-encryption plaintext DB before Room opens the file.
        SqlCipherSupport.migratePlaintextIfNeeded(
            context, File(context.getDatabasePath(AppDatabase.NAME).absolutePath), rawKey,
        )
        val factory = SupportOpenHelperFactory(SqlCipherSupport.passphrase(rawKey))

        return Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .openHelperFactory(factory)
            // WAL keeps writes fast and is the right journal mode for an append-heavy fact table.
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .addCallback(TriggerCallback)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    @Provides fun provideLocationSampleDao(db: AppDatabase): LocationSampleDao = db.locationSampleDao()
    @Provides fun providePlaceDao(db: AppDatabase): PlaceDao = db.placeDao()
    @Provides fun provideVisitDao(db: AppDatabase): VisitDao = db.visitDao()
    @Provides fun provideTripDao(db: AppDatabase): TripDao = db.tripDao()
    @Provides fun provideTrainingDao(db: AppDatabase): TrainingDao = db.trainingDao()
    @Provides fun provideGeofenceDao(db: AppDatabase): GeofenceDao = db.geofenceDao()
    @Provides fun provideBackupDao(db: AppDatabase): BackupDao = db.backupDao()

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE visits ADD COLUMN radiusMeters REAL NOT NULL DEFAULT 25.0")
        }
    }

    /** Adds the backup dirty-partition tracker table, its triggers, and seeds existing weeks. */
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `backup_dirty_partitions` " +
                    "(`stream` TEXT NOT NULL, `weekStart` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`stream`, `weekStart`))",
            )
            AppDatabase.DIRTY_TRIGGERS.forEach(db::execSQL)
            AppDatabase.DIRTY_SEED.forEach(db::execSQL)
        }
    }

    /**
     * Recreates the dirty-partition triggers. v4 created them with `INSERT OR IGNORE`, which SQLite
     * overrides with the triggering statement's ABORT policy — duplicate (stream, weekStart) marks
     * aborted every sample insert. The triggers were made `IF NOT EXISTS`, so they must be dropped
     * and rebuilt with the `WHERE NOT EXISTS` form. No table/data change.
     */
    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            AppDatabase.DIRTY_TRIGGER_NAMES.forEach { db.execSQL("DROP TRIGGER IF EXISTS $it") }
            AppDatabase.DIRTY_TRIGGERS.forEach(db::execSQL)
        }
    }

    /**
     * Flattens trips: each trip now carries its own transport mode + polyline, and the separate
     * `trip_segments` table is dropped (a multi-modal journey becomes consecutive single-mode trips).
     * Recreates `trips` to exactly match Room's schema (NOT NULL columns can't be `ADD COLUMN`-ed
     * without a default that Room would then reject), backfilling mode/polyline/distance from each
     * trip's first segment (lossy for old multi-segment trips, which maintenance re-derives anyway).
     */
    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            AppDatabase.DIRTY_TRIGGER_NAMES.forEach { db.execSQL("DROP TRIGGER IF EXISTS $it") }
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `trips_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`fromVisitId` INTEGER, `toVisitId` INTEGER, `startMs` INTEGER NOT NULL, " +
                    "`endMs` INTEGER NOT NULL, `dayEpoch` INTEGER NOT NULL, `mode` TEXT NOT NULL, " +
                    "`modeConfidence` REAL NOT NULL, `encodedPolyline` TEXT NOT NULL, " +
                    "`distanceMeters` REAL NOT NULL, `confirmed` INTEGER NOT NULL)",
            )
            db.execSQL(
                "INSERT INTO trips_new (id, fromVisitId, toVisitId, startMs, endMs, dayEpoch, " +
                    "mode, modeConfidence, encodedPolyline, distanceMeters, confirmed) " +
                    "SELECT t.id, t.fromVisitId, t.toVisitId, t.startMs, t.endMs, t.dayEpoch, " +
                    "COALESCE((SELECT s.mode FROM trip_segments s WHERE s.tripId = t.id ORDER BY s.startMs LIMIT 1), 'UNKNOWN'), " +
                    "COALESCE((SELECT s.modeConfidence FROM trip_segments s WHERE s.tripId = t.id ORDER BY s.startMs LIMIT 1), 0), " +
                    "COALESCE((SELECT s.encodedPolyline FROM trip_segments s WHERE s.tripId = t.id ORDER BY s.startMs LIMIT 1), ''), " +
                    "t.distanceMeters, t.confirmed FROM trips t",
            )
            db.execSQL("DROP TABLE trips")
            db.execSQL("ALTER TABLE trips_new RENAME TO trips")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_trips_startMs` ON `trips` (`startMs`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_trips_dayEpoch` ON `trips` (`dayEpoch`)")
            db.execSQL("DROP TABLE IF EXISTS trip_segments")
            db.execSQL("DELETE FROM backup_dirty_partitions WHERE stream = 'segments'")
            AppDatabase.DIRTY_TRIGGERS.forEach(db::execSQL)
        }
    }

    /**
     * Adds the immutable origin-anchor columns to `places` (nullable, so no Room-default conflict).
     * Existing rows get a NULL anchor and behave as before until they're recreated; backfill the
     * anchor from the current center/radius so established places keep a sensible origin.
     */
    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE places ADD COLUMN anchorLatitude REAL")
            db.execSQL("ALTER TABLE places ADD COLUMN anchorLongitude REAL")
            db.execSQL("ALTER TABLE places ADD COLUMN anchorRadiusMeters REAL")
            db.execSQL(
                "UPDATE places SET anchorLatitude = latitude, anchorLongitude = longitude, " +
                    "anchorRadiusMeters = radiusMeters",
            )
        }
    }

    /** Room creates entity tables on a fresh install but never triggers — add them here. */
    private val TriggerCallback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            AppDatabase.DIRTY_TRIGGERS.forEach(db::execSQL)
        }
    }
}
