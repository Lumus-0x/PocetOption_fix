package com.pocet.screenmonitor.di

import com.pocet.screenmonitor.service.SheetsAuthManager
import com.pocet.screenmonitor.service.SheetsAuthManagerImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {

    @Binds
    abstract fun bindSheetsAuthManager(impl: SheetsAuthManagerImpl): SheetsAuthManager
}