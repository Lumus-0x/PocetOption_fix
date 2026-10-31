package com.pocet.screenmonitor.di

import com.pocet.screenmonitor.service.AppsScriptClient
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider

/**
 * EntryPoint for accessing Hilt dependencies from non-Hilt classes like ScreenCaptureService.
 * Services cannot be @AndroidEntryPoint, so we use @EntryPoint with @InstallIn(SingletonComponent::class)
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ServiceEntryPoint {
    fun appsScriptClient(): Provider<AppsScriptClient>
}