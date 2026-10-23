package com.mmcdole.tether.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.mmcdole.tether.data.db.RuleDao
import com.mmcdole.tether.data.db.TetherDatabase
import com.mmcdole.tether.data.db.WorldDao
import com.mmcdole.tether.data.session.AndroidNetworkMonitor
import com.mmcdole.tether.data.session.MudSocketFactory
import com.mmcdole.tether.data.session.NetworkMonitor
import com.mmcdole.tether.data.session.TcpSocketFactory
import com.mmcdole.tether.data.session.TriggerAlerts
import com.mmcdole.tether.data.settings.SessionOptionsSource
import com.mmcdole.tether.data.settings.StoredSessionOptions
import com.mmcdole.tether.service.AndroidTriggerAlerts
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TetherDatabase =
        Room.databaseBuilder(context, TetherDatabase::class.java, TetherDatabase.NAME)
            .addMigrations(*TetherDatabase.MIGRATIONS)
            .build()

    @Provides
    fun provideWorldDao(database: TetherDatabase): WorldDao = database.worldDao()

    @Provides
    fun provideRuleDao(database: TetherDatabase): RuleDao = database.ruleDao()

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

    @Provides
    fun provideSocketFactory(factory: TcpSocketFactory): MudSocketFactory = factory

    @Provides
    fun provideNetworkMonitor(monitor: AndroidNetworkMonitor): NetworkMonitor = monitor

    @Provides
    fun provideTriggerAlerts(alerts: AndroidTriggerAlerts): TriggerAlerts = alerts

    @Provides
    @Singleton
    fun provideSessionOptions(stored: StoredSessionOptions): SessionOptionsSource = stored
}
