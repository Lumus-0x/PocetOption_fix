package com.pocet.screenmonitor.service

import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.api.services.sheets.v4.Sheets

interface SheetsAuthManager {

    fun initialize(signInOptions: GoogleSignInOptions)

    /**
     * Get authenticated Sheets service.
     * Returns null if user is not signed in and interactive sign-in is required.
     */
    suspend fun getSheetsService(): Sheets?

    /**
     * Get existing signed-in account or failure if interactive sign-in is required.
     * Does not throw - returns Result for caller to handle.
     */
    suspend fun getAccountOrNull(): Result<GoogleSignInAccount>

    fun getSignInIntent(): Intent?

    suspend fun handleSignInResult(resultCode: Int, intent: Intent?): Result<GoogleSignInAccount>

    fun signOut()

    fun isSignedIn(): Boolean
}