package com.pocet.screenmonitor.service

import android.accounts.Account
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.pocet.screenmonitor.util.EncryptedPrefsUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Collections

class SheetsAuthManagerImpl(private val context: Context) : SheetsAuthManager {

    private val authScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var googleSignInClient: GoogleSignInClient? = null
    private var signInOptions: GoogleSignInOptions? = null

    private val encryptedPrefs by lazy { EncryptedPrefsUtil(context) }

    companion object {
        private const val PREF_KEY_ACCOUNT_NAME = "google_account_name"
        private const val TAG = "SheetsAuthManagerImpl"
    }

    /**
     * Initialize with GoogleSignInOptions
     */
    override fun initialize(signInOptions: GoogleSignInOptions) {
        this.signInOptions = signInOptions
        googleSignInClient = GoogleSignIn.getClient(context, signInOptions)
    }

    /**
     * Get authenticated Sheets service, prompting for sign-in if needed
     */
    override suspend fun getSheetsService(): Sheets = withContext(Dispatchers.IO) {
        val account = getOrSignInAccount()
        val credential = createCredential(account)
        buildSheetsService(credential)
    }

    /**
     * Get existing signed-in account or prompt for sign-in
     */
    private suspend fun getOrSignInAccount(): GoogleSignInAccount = withContext(Dispatchers.IO) {
        // Try to get already signed-in account
        var account = GoogleSignIn.getLastSignedInAccount(context)

        if (account == null) {
            // Try to restore from saved account name
            val savedAccountName = encryptedPrefs.getString(PREF_KEY_ACCOUNT_NAME, null)
            if (savedAccountName != null) {
                account = GoogleSignIn.getSignedInAccountFromIntent(
                    // We need to trigger sign-in silently
                    com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(context, signInOptions!!)
                        .signInIntent
                ).await()
            }
        }

        if (account == null) {
            // Need interactive sign-in
            account = signInInteractively()
        }

        // Save account name for future silent sign-in attempts
        if (account != null) {
            encryptedPrefs.putString(PREF_KEY_ACCOUNT_NAME, account.account.name)
        }

        return@withContext account!!
    }

    /**
     * Perform interactive sign-in (must be called from Activity)
     * Note: This should be called from MainActivity with startActivityForResult
     */
    private suspend fun signInInteractively(): GoogleSignInAccount = withContext(Dispatchers.IO) {
        // This is a placeholder - actual sign-in must be done via Activity
        // The Activity should call startActivityForResult with signInIntent
        // and then we can get the result via GoogleSignIn.getSignedInAccountFromIntent
        throw IllegalStateException("Interactive sign-in required. Call from Activity.")
    }

    /**
     * Get sign-in intent for Activity to launch
     */
    override fun getSignInIntent(): android.content.Intent? {
        return googleSignInClient?.signInIntent
    }

    /**
     * Handle sign-in result from Activity
     */
    override suspend fun handleSignInResult(intent: android.content.Intent?): GoogleSignInAccount? = withContext(Dispatchers.IO) {
        if (intent == null) return@withContext null

        return@withContext try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(intent)
            val account = Tasks.await(task)
            encryptedPrefs.putString(PREF_KEY_ACCOUNT_NAME, account.account.name)
            account
        } catch (e: ApiException) {
            Timber.e(e, "Sign-in failed: ${e.statusCode}")
            null
        }
    }

    /**
     * Create GoogleAccountCredential for Sheets API
     */
    private fun createCredential(account: GoogleSignInAccount): GoogleAccountCredential {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            Collections.singleton(SheetsScopes.SPREADSHEETS)
        )
        credential.selectedAccount = account.account
        return credential
    }

    /**
     * Build Sheets service with credential
     */
    private fun buildSheetsService(credential: GoogleAccountCredential): Sheets {
        return Sheets.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("ScreenCaptureBalanceSync")
            .build()
    }

    /**
     * Sign out and clear stored credentials
     */
    override fun signOut() {
        authScope.launch {
            googleSignInClient?.signOut()?.addOnCompleteListener {
                encryptedPrefs.remove(PREF_KEY_ACCOUNT_NAME)
                Timber.d("Signed out and cleared credentials")
            }
        }
    }

    /**
     * Check if user is currently signed in
     */
    override fun isSignedIn(): Boolean {
        return GoogleSignIn.getLastSignedInAccount(context) != null
    }
}