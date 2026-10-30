package com.pocet.screenmonitor.service

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.HttpResponseException
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.model.ValueRange
import com.pocet.screenmonitor.model.BalanceResult
import com.pocet.screenmonitor.util.DateUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Collections

class SheetsRepository(
    private val authManager: SheetsAuthManager,
    private val dateUtils: DateUtils
) {

    private val SPREADSHEET_ID = "11I8BjFYYFDfXqTmu1u11lhJH-VIVdVS2tT8b5JqJmWQ"
    private val DATE_RANGE = "B6:B35"  // Column B, rows 6-35 for dates
    private val AMOUNT_COLUMN = "E"    // Column E for amounts

    companion object {
        private const val MAX_RETRIES = 3
        private const val BASE_DELAY_MS = 1000L
        private const val MAX_DELAY_MS = 30000L
    }

    /**
     * Write balance to Google Sheets at the row matching today's date
     */
    suspend fun writeBalance(result: BalanceResult): Result<Unit> = withContext(Dispatchers.IO) {
        retryWithBackoff(maxRetries = MAX_RETRIES) {
            writeBalanceInternal(result)
        }
    }

    private suspend fun writeBalanceInternal(result: BalanceResult): Result<Unit> {
        try {
            val service = authManager.getSheetsService()
            val today = dateUtils.getCurrentDateForSheets()

            Timber.d("Looking for date: $today in range $DATE_RANGE")

            // 1. Read dates from column B
            val dateResponse = service.spreadsheets().values()
                .get(SPREADSHEET_ID, DATE_RANGE)
                .execute()

            val dateValues = dateResponse.values
                ?: return Result.failure(Exception("No date values found in sheet"))

            Timber.d("Found ${dateValues.size} date rows")

            // 2. Find row matching today's date
            var targetRowIndex = -1
            for (i in dateValues.indices) {
                val cellValue = dateValues[i].firstOrNull()?.toString()?.trim() ?: ""
                val normalizedDate = dateUtils.normalizeForComparison(cellValue)
                if (normalizedDate == today) {
                    targetRowIndex = i
                    break
                }
            }

            if (targetRowIndex == -1) {
                Timber.w("Date $today not found in sheet rows 6-35")
                return Result.failure(Exception("Date not found in sheet: $today"))
            }

            // Row in sheet = targetRowIndex + 6 (since range starts at row 6)
            val targetRow = targetRowIndex + 6
            val writeRange = "$AMOUNT_COLUMN$targetRow"

            Timber.d("Writing amount ${result.amount} to $writeRange (row $targetRow)")

            // 3. Write amount to column E
            val valueRange = ValueRange().setValues(
                Collections.singletonList(Collections.singletonList(result.amount))
            )

            service.spreadsheets().values()
                .update(SPREADSHEET_ID, writeRange, valueRange)
                .setValueInputOption("USER_ENTERED")
                .execute()

            Timber.d("Successfully wrote balance to Google Sheets")
            Result.success(Unit)

        } catch (e: GoogleJsonResponseException) {
            Timber.e(e, "Google API error: ${e.statusCode} - ${e.message}")
            when (e.statusCode) {
                401, 403 -> Result.failure(AuthException("Authentication failed: ${e.message}", e))
                429 -> Result.failure(RateLimitException("Rate limited: ${e.message}", e))
                404 -> Result.failure(Exception("Spreadsheet not found: ${e.message}", e))
                else -> Result.failure(Exception("Google API error: ${e.message}", e))
            }
        } catch (e: HttpResponseException) {
            Timber.e(e, "HTTP error: ${e.statusCode}")
            Result.failure(Exception("HTTP error: ${e.message}", e))
        } catch (e: Exception) {
            Timber.e(e, "Failed to write balance")
            Result.failure(e)
        }
    }

    /**
     * Read current balance from sheet for verification
     */
    suspend fun readBalanceForDate(): Result<Double?> = withContext(Dispatchers.IO) {
        retryWithBackoff(maxRetries = MAX_RETRIES) {
            try {
                val service = authManager.getSheetsService()
                val today = dateUtils.getCurrentDateForSheets()

                val dateResponse = service.spreadsheets().values()
                    .get(SPREADSHEET_ID, DATE_RANGE)
                    .execute()

                val dateValues = dateResponse.values ?: return@retryWithBackoff Result.success(null)

                var targetRowIndex = -1
                for (i in dateValues.indices) {
                    val cellValue = dateValues[i].firstOrNull()?.toString()?.trim() ?: ""
                    val normalizedDate = dateUtils.normalizeForComparison(cellValue)
                    if (normalizedDate == today) {
                        targetRowIndex = i
                        break
                    }
                }

                if (targetRowIndex == -1) {
                    return@retryWithBackoff Result.success(null)
                }

                val targetRow = targetRowIndex + 6
                val readRange = "$AMOUNT_COLUMN$targetRow"

                val amountResponse = service.spreadsheets().values()
                    .get(SPREADSHEET_ID, readRange)
                    .execute()

                val amountValues = amountResponse.values
                if (amountValues != null && amountValues.isNotEmpty()) {
                    val value = amountValues[0][0].toString()
                    return@retryWithBackoff Result.success(value.toDoubleOrNull())
                }

                Result.success(null)
            } catch (e: Exception) {
                Timber.e(e, "Failed to read balance")
                Result.failure(e)
            }
        }
    }

    /**
     * Test connection to Google Sheets
     */
    suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val service = authManager.getSheetsService()
            service.spreadsheets().get(SPREADSHEET_ID).execute()
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Connection test failed")
            Result.failure(e)
        }
    }

    /**
     * Retry with exponential backoff and jitter
     */
    private inline fun <T> retryWithBackoff(
        maxRetries: Int,
        crossinline action: () -> Result<T>
    ): Result<T> {
        var attempt = 0
        var lastException: Exception? = null

        while (attempt <= maxRetries) {
            val result = action()
            if (result.isSuccess) return result

            lastException = result.exceptionOrNull()

            // Don't retry on auth errors - need re-auth
            if (lastException is AuthException) {
                return result
            }

            attempt++
            if (attempt <= maxRetries) {
                val delay = minOf(
                    BASE_DELAY_MS * (2L shl (attempt - 1)),
                    MAX_DELAY_MS
                )
                // Add jitter
                val jitter = (Math.random() * 1000).toLong()
                Thread.sleep(delay + jitter)
                Timber.d("Retry attempt $attempt after ${delay + jitter}ms")
            }
        }

        return Result.failure(lastException ?: Exception("Max retries exceeded"))
    }

    sealed class SheetsException(message: String, cause: Throwable? = null) : Exception(message, cause) {
        data class AuthException(
            val msg: String,
            val cause: Throwable? = null
        ) : SheetsException(msg, cause)

        data class RateLimitException(
            val msg: String,
            val cause: Throwable? = null
        ) : SheetsException(msg, cause)
    }

    companion object {
        typealias AuthException = SheetsException.AuthException
        typealias RateLimitException = SheetsException.RateLimitException
    }
}