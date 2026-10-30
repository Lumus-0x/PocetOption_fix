package com.pocet.screenmonitor.service

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.api.services.sheets.v4.Sheets
import kotlinx.coroutines.CoroutineScope

interface SheetsAuthManager {

    /**
     * Initialize with GoogleSignInOptions
     */
    fun initialize(signInOptions: GoogleSignInOptions)

    /**
     * Get authenticated Sheets service, prompting for sign-in if needed
     */
    suspend fun getSheetsService(): Sheets

    /**
     * Get sign-in intent for Activity to launch
     */
    fun getSignInIntent(): android.content.Intent?

    /**
     * Handle sign-in result from Activity
     */
    suspend fun handleSignInResult(intent: android.content.Intent?): com.google.android.gms.auth.api.signin.GoogleSignInAccount?

    /**
     * Sign out and clear stored credentials
     */
    fun signOut()

    /**
     * Check if user is currently signed in
     */
    fun isSignedIn(): Boolean
}