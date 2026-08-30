package net.extrawdw.apps.locationhistory.di

import android.content.Context
import androidx.room3.RoomDatabase
import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.extrawdw.apps.locationhistory.data.db.ApiAccessDao
import net.extrawdw.apps.locationhistory.data.db.ApiAccessDatabase
import net.extrawdw.apps.locationhistory.data.db.ApiPlaceGrantDao
import net.extrawdw.apps.locationhistory.data.db.AnnotationDao
import net.extrawdw.apps.locationhistory.data.db.AppDatabase
import net.extrawdw.apps.locationhistory.data.db.AppMigrations
import net.extrawdw.apps.locationhistory.data.db.BackupDao
import net.extrawdw.apps.locationhistory.data.db.ConceptDao
import net.extrawdw.apps.locationhistory.data.db.GeofenceDao
import net.extrawdw.apps.locationhistory.data.db.LocationSampleDao
import net.extrawdw.apps.locationhistory.data.db.PlaceDao
import net.extrawdw.apps.locationhistory.data.db.PlaceCoordinateRepairDao
import net.extrawdw.apps.locationhistory.data.db.SearchDao
import net.extrawdw.apps.locationhistory.data.db.TagDao
import net.extrawdw.apps.locationhistory.data.db.TripDao
import net.extrawdw.apps.locationhistory.data.db.VisitDao
import net.extrawdw.apps.locationhistory.security.DatabaseKeyStore
import net.extrawdw.apps.locationhistory.security.SqlCipherSupport
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver
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
        // SQLCipher does not auto-load its native library; load it before plaintext detection or
        // the Room 3 SQLiteDriver opens a connection.
        System.loadLibrary("sqlcipher")
        val rawKey = keyStore.databasePassphrase()
        val databaseFile = context.getDatabasePath(AppDatabase.NAME)
        val driver = try {
            // Transparently upgrade any pre-encryption plaintext DB before Room opens the file.
            SqlCipherSupport.migratePlaintextIfNeeded(context, databaseFile, rawKey)
            SQLCipherDriver(SqlCipherSupport.passphrase(rawKey), null, null)
        } finally {
            // The driver owns its derived SQLCipher literal; the Keystore-unwrapped raw key no
            // longer needs to remain in this scope.
            rawKey.fill(0)
        }

        return Room.databaseBuilder(context, AppDatabase::class.java, databaseFile.absolutePath)
            .setDriver(driver)
            // WAL keeps writes fast and is the right journal mode for an append-heavy fact table.
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(TriggerCallback)
            // Real migrations from the first public release on (v1 baseline is frozen). A missing
            // upgrade migration FAILS FAST (Room throws) rather than silently wiping user data — that
            // is intentional, so it's caught in testing/CI. Only a version *downgrade* (rare) still
            // rebuilds rather than crash. See [AppMigrations].
            .addMigrations(*AppMigrations.ALL)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()
    }

    @Provides
    fun provideLocationSampleDao(db: AppDatabase): LocationSampleDao = db.locationSampleDao()

    @Provides
    fun providePlaceDao(db: AppDatabase): PlaceDao = db.placeDao()

    @Provides
    fun providePlaceCoordinateRepairDao(db: AppDatabase): PlaceCoordinateRepairDao =
        db.placeCoordinateRepairDao()

    @Provides
    fun provideVisitDao(db: AppDatabase): VisitDao = db.visitDao()

    @Provides
    fun provideTripDao(db: AppDatabase): TripDao = db.tripDao()

    @Provides
    fun provideGeofenceDao(db: AppDatabase): GeofenceDao = db.geofenceDao()

    @Provides
    fun provideBackupDao(db: AppDatabase): BackupDao = db.backupDao()

    @Provides
    fun provideTagDao(db: AppDatabase): TagDao = db.tagDao()

    @Provides
    fun provideAnnotationDao(db: AppDatabase): AnnotationDao = db.annotationDao()

    @Provides
    fun provideConceptDao(db: AppDatabase): ConceptDao = db.conceptDao()

    @Provides
    fun provideSearchDao(db: AppDatabase): SearchDao = db.searchDao()

    /**
     * Standalone, unencrypted database for the third-party API audit trail and durable place-grant
     * ledger. It stays separate from [AppDatabase], but its schema is migrated normally so recovery
     * never discards consumer-app authorization scope.
     */
    @Provides
    @Singleton
    fun provideApiAccessDatabase(@ApplicationContext context: Context): ApiAccessDatabase =
        Room.databaseBuilder(context, ApiAccessDatabase::class.java, ApiAccessDatabase.NAME)
            .addMigrations(ApiAccessDatabase.MIGRATION_1_2)
            .build()

    @Provides
    fun provideApiAccessDao(db: ApiAccessDatabase): ApiAccessDao = db.apiAccessDao()

    @Provides
    fun provideApiPlaceGrantDao(db: ApiAccessDatabase): ApiPlaceGrantDao = db.apiPlaceGrantDao()

    /**
     * Room owns the entity tables and FTS5 sync triggers. The backup dirty-partition triggers remain
     * application-specific and therefore must be installed for a fresh database here.
     */
    private val TriggerCallback = object : RoomDatabase.Callback() {
        override suspend fun onCreate(connection: SQLiteConnection) {
            AppDatabase.DIRTY_TRIGGERS.forEach(connection::execSQL)
        }
    }
}
