package com.pocet.screenmonitor.service

import android.accounts.Account
import android.app.Activity
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
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Collections

class SheetsAuthManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    googleSignInOptions: GoogleSignInOptions
) : SheetsAuthManager {

    private val authScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var googleSignInClient: GoogleSignInClient? = null
    private var signInOptions: GoogleSignInOptions? = null

    private val encryptedPrefs by lazy { EncryptedPrefsUtil(context) }

    companion object {
        private const val PREF_KEY_ACCOUNT_NAME = "google_account_name"
        private const val TAG = "SheetsAuthManagerImpl"
    }

    init {
        initialize(googleSignInOptions)
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
     * Returns null if not signed in and interactive sign-in is required
     */
    override suspend fun getSheetsService(): Sheets? = withContext(Dispatchers.IO) {
        val accountResult = getAccountOrNull()
        if (!accountResult.isSuccess) {
            return@withContext null
        }
        val account = accountResult.getOrNull()
        if (account == null) {
            return@withContext null
        }
        val credential = createCredential(account)
        buildSheetsService(credential)
    }

    /**
     * Get existing signed-in account or null if interactive sign-in is required
     * Does NOT throw - returns Result for caller to handle
     */
    suspend fun getAccountOrNull(): Result<GoogleSignInAccount> = withContext(Dispatchers.IO) {
        // Try to get already signed-in account
        var account = GoogleSignIn.getLastSignedInAccount(context)

        if (account == null) {
            // Try to restore from saved account name via silent sign-in
            val savedAccountName = encryptedPrefs.getString(PREF_KEY_ACCOUNT_NAME, null)
            if (savedAccountName != null) {
                val client = GoogleSignIn.getClient(context, signInOptions!!)
                try {
                    account = Tasks.await(client.silentSignIn())
                } catch (e: Exception) {
                    Timber.w(e, "Silent sign-in failed, will need interactive sign-in")
                    // Silent sign-in failed (token expired, account removed, etc.)
                    // Return failure so caller can trigger interactive sign-in
                    return@withContext Result.failure(Exception("Silent sign-in failed: ${e.message}"))
                }
            }
        }

        if (account == null) {
            // No account available - need interactive sign-in
            return@withContext Result.failure(Exception("No signed-in account. Interactive sign-in required."))
        }

        // Save account name for future silent sign-in attempts
        account.account?.name?.let { name ->
            if (name.isNotEmpty()) {
                encryptedPrefs.putString(PREF_KEY_ACCOUNT_NAME, name)
            }
        }

        Result.success(account)
    }

    /**
     * Get sign-in intent for Activity to launch
     */
    override fun getSignInIntent(): android.content.Intent? {
        return googleSignInClient?.signInIntent
    }

    /**
     * Handle sign-in result from Activity
     * @param resultCode The result code from the activity (Activity.RESULT_OK, Activity.RESULT_CANCELED, etc.)
     * @param intent The intent data from the sign-in activity
     * @return Result containing the signed-in account or error details
     */
    override suspend fun handleSignInResult(resultCode: Int, intent: android.content.Intent?): Result<GoogleSignInAccount> = withContext(Dispatchers.IO) {
        Timber.d("handleSignInResult: resultCode=$resultCode, intent=${intent != null}")

        // Always try to process the intent to get detailed error info
        // Even if resultCode is CANCELED, the intent might contain error details
        if (intent == null) {
            val msg = "No sign-in intent data received (resultCode: $resultCode)"
            Timber.w(msg)
            return@withContext Result.failure(Exception(msg))
        }

        return@withContext try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(intent)
            val account = Tasks.await(task)

            // Save account name for future silent sign-in attempts
            val accountName = account.account?.name ?: ""
            if (accountName.isNotEmpty()) {
                encryptedPrefs.putString(PREF_KEY_ACCOUNT_NAME, accountName)
            }

            Timber.d("Sign-in successful for account: $accountName")
            Result.success(account)
        } catch (e: ApiException) {
            // Get detailed error message based on status code
            val errorMsg = when (e.statusCode) {
                com.google.android.gms.common.api.CommonStatusCodes.CANCELED -> "User cancelled sign-in"
                com.google.android.gms.common.api.CommonStatusCodes.NETWORK_ERROR -> "Network error during sign-in"
                com.google.android.gms.common.api.CommonStatusCodes.INTERNAL_ERROR -> "Internal error during sign-in"
                com.google.android.gms.common.api.CommonStatusCodes.API_NOT_CONNECTED -> "Google Play Services not connected"
                com.google.android.gms.common.api.CommonStatusCodes.DEVELOPER_ERROR -> "Developer error: check OAuth configuration and SHA-1 fingerprint"
                com.google.android.gms.common.api.CommonStatusCodes.RESOLUTION_REQUIRED -> "Resolution required: ${e.message}"
                else -> "Google Sign-In failed (${e.statusCode}): ${e.message ?: "Unknown error"}"
            }
            Timber.e(e, "Sign-in error: $errorMsg")
            Result.failure(Exception(errorMsg))
        } catch (e: Exception) {
            val errorMsg = "Unexpected error during sign-in: ${e.message ?: e::class.simpleName}"
            Timber.e(e, errorMsg)
            Result.failure(Exception(errorMsg))
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
        account.account?.let { credential.selectedAccount = it }
        return credential
    }

    /**
     * Build Sheets service with credential
     */
    private fun buildSheetsService(credential: GoogleAccountCredential): Sheets {
        return Sheets.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("Pocket Option Helper")
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