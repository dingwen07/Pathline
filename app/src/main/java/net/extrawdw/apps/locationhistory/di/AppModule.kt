package net.extrawdw.apps.locationhistory.di

import android.content.Context
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.extrawdw.apps.locationhistory.data.places.PlacesGateway
import net.extrawdw.apps.locationhistory.data.places.PlacesPort
import net.extrawdw.apps.locationhistory.core.coordinates.MainlandChinaRegion
import net.extrawdw.apps.locationhistory.core.coordinates.MainlandRegionClassifier
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun providePlacesPort(gateway: PlacesGateway): PlacesPort = gateway

    @Provides
    @Singleton
    fun provideMainlandRegionClassifier(region: MainlandChinaRegion): MainlandRegionClassifier =
        region
}
