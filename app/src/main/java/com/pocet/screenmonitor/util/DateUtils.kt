package com.pocet.screenmonitor.util

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DateUtils {

    // Google Sheets date format - common formats to try
    // Using DateTimeFormatter which is thread-safe (unlike SimpleDateFormat)
    private val sheetDateFormatters = listOf(
        DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US),
        DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US),
        DateTimeFormatter.ofPattern("MM-dd-yyyy", Locale.US),
        DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.US)
    )

    private val currentDateFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.US)
    private val isoDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    /**
     * Get current date formatted for Google Sheets (MM/dd/yyyy)
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
     * Normalize a date string to MM/dd/yyyy format for comparison
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