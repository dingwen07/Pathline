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
        TripSegmentEntity::class,
        GeofenceEntity::class,
        StateTrainingExampleEntity::class,
        TransportTrainingExampleEntity::class,
    ],
    version = 3,
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

    companion object {
        const val NAME = "pathline.db"
    }
}
