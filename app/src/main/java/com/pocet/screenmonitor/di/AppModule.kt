package com.pocet.screenmonitor.di

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.pocet.screenmonitor.service.OcrProcessor
import com.pocet.screenmonitor.service.SheetsAuthManager
import com.pocet.screenmonitor.service.SheetsRepository
import com.pocet.screenmonitor.service.TextParser
import com.pocet.screenmonitor.util.BatteryOptimizationUtils
import com.pocet.screenmonitor.util.DateUtils
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideGoogleSignInOptions(): GoogleSignInOptions {
        return GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestScopes(com.google.android.gms.common.api.Scope(SheetsScopes.SPREADSHEETS))
            .requestEmail()
            .build()
    }

    @Provides
    @Singleton
    fun provideSheetsService(
        authManager: SheetsAuthManager
    ): Sheets = authManager.getSheetsService()

    @Provides
    @Singleton
    fun provideOcrProcessor(): OcrProcessor {
        return OcrProcessor()
    }

    @Provides
    @Singleton
    fun provideTextParser(): TextParser {
        return TextParser()
    }

    @Provides
    @Singleton
    fun provideDateUtils(): DateUtils {
        return DateUtils()
    }

    @Provides
    @Singleton
    fun provideBatteryOptimizationUtils(context: Context): BatteryOptimizationUtils {
        return BatteryOptimizationUtils(context)
    }
}