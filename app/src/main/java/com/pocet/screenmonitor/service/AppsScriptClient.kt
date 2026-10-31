package com.pocet.screenmonitor.service

import com.google.gson.Gson
import com.pocet.screenmonitor.model.BalanceResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Client for sending balance data to Google Apps Script Web App.
 *
 * The Apps Script should be deployed as a Web App with:
 * - Execute as: Me
 * - Who has access: Anyone (or Anyone with link)
 *
 * Expected Apps Script doPost(e) format:
 * ```javascript
 * function doPost(e) {
 *   const data = JSON.parse(e.postData.contents);
 *   // data: { date: "MM/dd/yyyy", amount: "1234.56", accountType: "REAL" }
 *   const sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName('Лист1');
 *   const dates = sheet.getRange('B:B').getValues().flat();
 *   const row = dates.findIndex(d => d === data.date) + 1;
 *   if (row > 0) sheet.getRange(`E${row}`).setValue(data.amount);
 *   return ContentService.createTextOutput('OK');
 * }
 * ```
 */
class AppsScriptClient private constructor(
    private val webAppUrl: String,
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {

    companion object {
        private const val TIMEOUT_SECONDS = 30L
        private const val CONTENT_TYPE = "application/json; charset=utf-8"

        fun create(webAppUrl: String): AppsScriptClient {
            val client = OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
            return AppsScriptClient(webAppUrl, client, Gson())
        }
    }

    /**
     * Sends balance data to the Apps Script Web App.
     *
     * @param result BalanceResult with amount, accountType, and current date
     * @return Result with success status and optional error message
     */
    suspend fun sendBalance(result: BalanceResult): AppsScriptResult {
        val date = com.pocet.screenmonitor.util.DateUtils.formatSheetDate(System.currentTimeMillis())

        val payload = mapOf(
            "date" to date,
            "amount" to result.amount,
            "accountType" to result.accountType.name
        )

        val json = gson.toJson(payload)
        val body = json.toRequestBody(CONTENT_TYPE.toMediaType())
        val request = Request.Builder()
            .url(webAppUrl)
            .post(body)
            .build()

        return try {
            okHttpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    Timber.d("Apps Script response: $responseBody")
                    AppsScriptResult.Success(responseBody)
                } else {
                    Timber.e("Apps Script error: ${response.code()} - $responseBody")
                    AppsScriptResult.Error("HTTP ${response.code()}: $responseBody")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Apps Script request failed")
            AppsScriptResult.Error(e.message ?: "Network error")
        }
    }
}

/** Result of Apps Script request */
sealed class AppsScriptResult {
    data class Success(val response: String) : AppsScriptResult()
    data class Error(val message: String) : AppsScriptResult()

    val isSuccess: Boolean
        get() = this is Success

    val errorMessage: String?
        get() = when (this) {
            is Error -> message
            else -> null
        }
}