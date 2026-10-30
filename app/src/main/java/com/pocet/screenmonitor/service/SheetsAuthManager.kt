package com.pocet.screenmonitor.service

import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.api.services.sheets.v4.Sheets

interface SheetsAuthManager {

    fun initialize(signInOptions: GoogleSignInOptions)

    suspend fun getSheetsService(): Sheets

    fun getSignInIntent(): Intent?

    suspend fun handleSignInResult(intent: Intent?): GoogleSignInAccount?

    fun signOut()

    fun isSignedIn(): Boolean
}