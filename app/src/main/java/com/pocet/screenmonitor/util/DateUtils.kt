package com.pocet.screenmonitor.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DateUtils {

    // Google Sheets date format - common formats to try
    private val sheetDateFormatters = listOf(
        SimpleDateFormat("MM/dd/yyyy", Locale.US),
        SimpleDateFormat("yyyy-MM-dd", Locale.US),
        SimpleDateFormat("dd/MM/yyyy", Locale.US),
        SimpleDateFormat("MM-dd-yyyy", Locale.US),
        SimpleDateFormat("yyyy/MM/dd", Locale.US)
    ).also { formatters ->
        formatters.forEach { it.timeZone = TimeZone.getDefault() }
    }

    private val currentDateFormatter = SimpleDateFormat("MM/dd/yyyy", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    private val isoDateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    /**
     * Get current date formatted for Google Sheets (MM/dd/yyyy)
     */
    fun getCurrentDateForSheets(): String {
        return currentDateFormatter.format(Date())
    }

    /**
     * Get current date in ISO format (yyyy-MM-dd)
     */
    fun getCurrentDateIso(): String {
        return isoDateFormatter.format(Date())
    }

    /**
     * Try to parse a date string from Google Sheets using multiple formats
     */
    fun parseSheetDate(dateString: String): String? {
        val trimmed = dateString.trim()
        for (formatter in sheetDateFormatters) {
            try {
                val date = formatter.parse(trimmed)
                return currentDateFormatter.format(date!!)
            } catch (e: Exception) {
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