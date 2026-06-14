package com.tangdun.app.di

import android.content.Context
import com.tangdun.app.TangDunApp
import com.tangdun.app.data.local.AppDatabase
import com.tangdun.app.data.local.dao.*
import com.tangdun.app.data.remote.FoodRecognitionService
import com.tangdun.app.data.local.BackupManager
import com.tangdun.app.domain.algorithm.AlertEngine
import com.tangdun.app.domain.algorithm.CGMPreprocessor
import com.tangdun.app.domain.algorithm.CarbCalculator
import com.tangdun.app.domain.algorithm.FeatureExtractor
import com.tangdun.app.domain.algorithm.InsulinCalculator
import com.tangdun.app.domain.algorithm.NightMonitor
import com.tangdun.app.domain.algorithm.TrendCalculator
import com.tangdun.app.domain.algorithm.SmartAdvisor
import com.tangdun.app.util.SettingsManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        // ★ 使用TangDunApp的单例方法，确保BroadcastReceiver/Service等非Hilt
        // 代码与ViewModel共享同一个Room实例，保证Flow InvalidationTracker同步
        return TangDunApp.getDatabase(context)
    }

    @Provides
    @Singleton
    fun provideGlucoseDao(database: AppDatabase): GlucoseDao {
        return database.glucoseDao()
    }

    @Provides
    @Singleton
    fun provideMealDao(database: AppDatabase): MealDao {
        return database.mealDao()
    }

    @Provides
    @Singleton
    fun provideExerciseDao(database: AppDatabase): ExerciseDao {
        return database.exerciseDao()
    }

    @Provides
    @Singleton
    fun provideInsulinDao(database: AppDatabase): InsulinDao {
        return database.insulinDao()
    }

    @Provides
    @Singleton
    fun provideAlertDao(database: AppDatabase): AlertDao {
        return database.alertDao()
    }

    @Provides
    @Singleton
    fun provideChatDao(database: AppDatabase): ChatDao {
        return database.chatDao()
    }

    @Provides
    @Singleton
    fun provideSettingsManager(@ApplicationContext context: Context): SettingsManager {
        return SettingsManager(context)
    }

    @Provides
    @Singleton
    fun provideFoodRecognitionService(@ApplicationContext context: Context): FoodRecognitionService {
        return FoodRecognitionService(context)
    }

    @Provides
    @Singleton
    fun provideCGMPreprocessor(): CGMPreprocessor {
        return CGMPreprocessor()
    }

    @Provides
    @Singleton
    fun provideFeatureExtractor(): FeatureExtractor {
        return FeatureExtractor()
    }

    @Provides
    @Singleton
    fun provideAlertEngine(): AlertEngine {
        return AlertEngine()
    }

    @Provides
    @Singleton
    fun provideSmartAdvisor(): SmartAdvisor {
        return SmartAdvisor()
    }

    @Provides
    @Singleton
    fun provideInsulinCalculator(settingsManager: SettingsManager): InsulinCalculator {
        return InsulinCalculator(settingsManager)
    }

    @Provides
    @Singleton
    fun provideCarbCalculator(): CarbCalculator {
        return CarbCalculator()
    }

    @Provides
    @Singleton
    fun provideNightMonitor(): NightMonitor {
        return NightMonitor()
    }

    @Provides
    @Singleton
    fun provideBackupManager(
        @ApplicationContext context: Context,
        glucoseDao: GlucoseDao,
        mealDao: MealDao,
        insulinDao: InsulinDao,
        exerciseDao: ExerciseDao,
        alertDao: AlertDao
    ): BackupManager {
        return BackupManager(context, glucoseDao, mealDao, insulinDao, exerciseDao, alertDao)
    }

    @Provides
    @Singleton
    fun provideSleepDao(database: AppDatabase): SleepDao {
        return database.sleepDao()
    }

    @Provides
    @Singleton
    fun provideBloodPressureDao(database: AppDatabase): BloodPressureDao {
        return database.bloodPressureDao()
    }

    @Provides
    @Singleton
    fun provideWeightDao(database: AppDatabase): WeightDao {
        return database.weightDao()
    }

    @Provides
    @Singleton
    fun provideKetoneDao(database: AppDatabase): KetoneDao {
        return database.ketoneDao()
    }

    @Provides
    @Singleton
    fun provideMedicationDao(database: AppDatabase): MedicationDao {
        return database.medicationDao()
    }

    @Provides
    @Singleton
    fun provideSymptomDao(database: AppDatabase): SymptomDao {
        return database.symptomDao()
    }

    @Provides
    @Singleton
    fun provideTrendCalculator(): TrendCalculator {
        return TrendCalculator()
    }
}
