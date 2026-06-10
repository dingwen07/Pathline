package net.extrawdw.apps.locationhistory.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
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
import net.extrawdw.apps.locationhistory.data.db.SearchDao
import net.extrawdw.apps.locationhistory.data.db.TagDao
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
    fun provideVisitDao(db: AppDatabase): VisitDao = db.visitDao()

    @Provides
    fun provideTripDao(db: AppDatabase): TripDao = db.tripDao()

    @Provides
    fun provideTrainingDao(db: AppDatabase): TrainingDao = db.trainingDao()

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
     * Standalone, unencrypted log DB for the third-party API audit trail (which app read what, when).
     * Kept apart from [AppDatabase] so logging never touches the frozen v1 schema or the backup
     * engine. A schema change here may rebuild destructively — the audit log is disposable.
     */
    @Provides
    @Singleton
    fun provideApiAccessDatabase(@ApplicationContext context: Context): ApiAccessDatabase {
        fun build(): ApiAccessDatabase =
            Room.databaseBuilder(context, ApiAccessDatabase::class.java, ApiAccessDatabase.NAME)
                // A real migration where one is known (it also carries the place-grant ledger,
                // which is worth keeping); anything unexpected still rebuilds destructively.
                .addMigrations(ApiAccessDatabase.MIGRATION_1_2)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()

        val db = build()
        return try {
            // The schema of this disposable audit DB can change without a version bump. Force the open
            // now: a schema-identity mismatch throws here, and since the log is disposable we simply
            // drop the file and recreate it fresh with the current schema (no migration).
            db.openHelper.writableDatabase
            db
        } catch (t: Throwable) {
            runCatching { db.close() }
            context.deleteDatabase(ApiAccessDatabase.NAME)
            build()
        }
    }

    @Provides
    fun provideApiAccessDao(db: ApiAccessDatabase): ApiAccessDao = db.apiAccessDao()

    @Provides
    fun provideApiPlaceGrantDao(db: ApiAccessDatabase): ApiPlaceGrantDao = db.apiPlaceGrantDao()

    /**
     * Room creates entity tables on a fresh install but never triggers or virtual tables — add the
     * backup dirty-partition triggers and the FTS5 search tables/triggers here. (On upgrade these come
     * from the migration instead; a fresh DB has no rows to backfill.)
     */
    private val TriggerCallback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            AppDatabase.DIRTY_TRIGGERS.forEach(db::execSQL)
            AppDatabase.FTS_CREATE.forEach(db::execSQL)
        }
    }
}
