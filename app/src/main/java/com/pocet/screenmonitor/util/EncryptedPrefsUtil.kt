package com.pocet.screenmonitor.util

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import timber.log.Timber
import java.io.IOException
import java.security.GeneralSecurityException

class EncryptedPrefsUtil(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        createEncryptedPrefs()
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKeyAlias = try {
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        } catch (e: GeneralSecurityException) {
            Timber.e(e, "Failed to create master key, falling back to regular SharedPreferences")
            return createFallbackPrefs()
        } catch (e: IOException) {
            Timber.e(e, "IO Exception creating master key, falling back to regular SharedPreferences")
            return createFallbackPrefs()
        }

        return try {
            EncryptedSharedPreferences.create(
                "secure_prefs",
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: GeneralSecurityException) {
            Timber.e(e, "Failed to create encrypted shared preferences, falling back to regular SharedPreferences")
            createFallbackPrefs()
        } catch (e: IOException) {
            Timber.e(e, "IO Exception creating encrypted shared preferences, falling back to regular SharedPreferences")
            createFallbackPrefs()
        }
    }

    private fun createFallbackPrefs(): SharedPreferences {
        Timber.w("Using fallback regular SharedPreferences (not encrypted)")
        return context.getSharedPreferences("secure_prefs_fallback", Context.MODE_PRIVATE)
    }

    fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun getString(key: String, defaultValue: String?): String? {
        return prefs.getString(key, defaultValue)
    }

    fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return prefs.getBoolean(key, defaultValue)
    }

    fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    fun getLong(key: String, defaultValue: Long): Long {
        return prefs.getLong(key, defaultValue)
    }

    fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    fun getInt(key: String, defaultValue: Int): Int {
        return prefs.getInt(key, defaultValue)
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun contains(key: String): Boolean {
        return prefs.contains(key)
    }
}