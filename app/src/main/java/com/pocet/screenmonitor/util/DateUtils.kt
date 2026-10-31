package com.pocet.screenmonitor.util

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

class DateUtils {

    // User's date format: DD.MM.YYYY (e.g., 06.11.2026)
    private val sheetDateFormatters = listOf(
        DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.US),
        DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US),
        DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US),
        DateTimeFormatter.ofPattern("MM-dd-yyyy", Locale.US),
        DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.US)
    )

    // Output format for Apps Script: DD.MM.YYYY
    private val currentDateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.US)
    private val isoDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    /**
     * Get current date formatted for Apps Script / Google Sheets (DD.MM.YYYY)
     * User's format: 06.11.2026
     */
    fun getCurrentDateForSheets(): String {
        return LocalDate.now().format(currentDateFormatter)
    }

    /**
     * Get current date in ISO format (yyyy-MM-dd)
     */
    fun getCurrentDateIso(): String {
        return LocalDate.now().format(isoDateFormatter)
    }

    /**
     * Try to parse a date string from Google Sheets using multiple formats
     * Returns normalized date in DD.MM.YYYY format
     */
    fun parseSheetDate(dateString: String): String? {
        val trimmed = dateString.trim()
        for (formatter in sheetDateFormatters) {
            try {
                val date = LocalDate.parse(trimmed, formatter)
                return date.format(currentDateFormatter)
            } catch (e: DateTimeParseException) {
                // Try next format
            }
        }
        return null
    }

    /**
     * Normalize a date string to DD.MM.YYYY format for comparison
     */
    fun normalizeForComparison(dateString: String): String? {
        return parseSheetDate(dateString)
    }

    /**
     * Check if a date string matches today
     */
    fun isToday(dateString: String): Boolean {
        val normalized = normalizeForComparison(dateString)
        val today = getCurrentDateForSheets()
        return normalized == today
    }
}