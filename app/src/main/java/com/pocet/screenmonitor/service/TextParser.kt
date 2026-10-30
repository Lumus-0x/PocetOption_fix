package com.pocet.screenmonitor.service

import com.google.mlkit.vision.text.Text
import com.pocet.screenmonitor.model.BalanceResult
import timber.log.Timber

class TextParser {

    // Patterns to detect "qt real usd" or "qt demo usd" (case insensitive)
    private val qtRealPatterns = listOf(
        "qt real usd",
        "real usd",
        "real account",
        "real balance"
    )

    private val qtDemoPatterns = listOf(
        "qt demo usd",
        "demo usd",
        "demo account",
        "demo balance"
    )

    // Currency amount patterns
    private val amountPatterns = listOf(
        // $1,234.56 or 1,234.56
        """\$?\s*(\d{1,3}(?:,\d{3})*(?:\.\d{2})?)""".toRegex(),
        // 1234.56
        """\$?\s*(\d+(?:\.\d{2})?)""".toRegex(),
        // 1.234,56 (European format)
        """\$?\s*(\d{1,3}(?:\.\d{3})*(?:,\d{2})?)""".toRegex()
    )

    /**
     * Parse OCR text result to find balance information
     * @param text ML Kit text recognition result
     * @param targetAccountType If specified, only look for this account type (REAL or DEMO). If null, detect both.
     */
    fun parseText(text: Text, targetAccountType: BalanceResult.AccountType? = null): BalanceResult {
        val fullText = text.text.lowercase()
        Timber.d("OCR Full Text: $fullText")

        // Determine account type to look for
        val accountType = targetAccountType ?: detectAccountType(fullText)

        if (accountType == BalanceResult.AccountType.UNKNOWN) {
            return BalanceResult.unknown(fullText)
        }

        // Extract amount BELOW the account type keyword (primary strategy)
        var amount = extractAmountBelowKeyword(text, accountType)

        // Fallback: extract amount near keyword (same line or adjacent)
        if (amount == null) {
            amount = extractAmountNearKeyword(text, accountType)
        }

        // Final fallback: extract any amount from full text
        if (amount == null) {
            amount = extractAnyAmount(fullText)
            if (amount != null) {
                return BalanceResult(accountType, amount, 0.2f, fullText)
            }
            return BalanceResult.unknown(fullText)
        }

        // Calculate confidence based on detection strategy
        val confidence = calculateConfidence(text, accountType, amount)

        return BalanceResult(accountType, amount, confidence, fullText)
    }

    private fun detectAccountType(text: String): BalanceResult.AccountType {
        val lowerText = text.lowercase()

        for (pattern in qtRealPatterns) {
            if (lowerText.contains(pattern.lowercase())) {
                return BalanceResult.AccountType.REAL
            }
        }

        for (pattern in qtDemoPatterns) {
            if (lowerText.contains(pattern.lowercase())) {
                return BalanceResult.AccountType.DEMO
            }
        }

        return BalanceResult.AccountType.UNKNOWN
    }

    /**
     * Primary strategy: Find keyword, then look for numbers in lines BELOW it
     * This handles the case where "qt real usd" is a header and balance is on next lines
     */
    private fun extractAmountBelowKeyword(text: Text, accountType: BalanceResult.AccountType): Double? {
        val keywords = when (accountType) {
            BalanceResult.AccountType.REAL -> qtRealPatterns
            BalanceResult.AccountType.DEMO -> qtDemoPatterns
            else -> return null
        }

        // Flatten all lines with their block/line indices for positional analysis
        val allLines = mutableListOf<LineWithPosition>()
        for ((blockIndex, block) in text.textBlocks.withIndex()) {
            for ((lineIndex, line) in block.lines.withIndex()) {
                allLines.add(LineWithPosition(
                    blockIndex = blockIndex,
                    lineIndex = lineIndex,
                    text = line.text,
                    boundingBox = line.boundingBox
                ))
            }
        }

        // Find lines containing keywords
        for ((i, line) in allLines.withIndex()) {
            val lineTextLower = line.text.lowercase()
            val hasKeyword = keywords.any { lineTextLower.contains(it.lowercase()) }

            if (hasKeyword) {
                Timber.d("Found keyword at line $i: ${line.text}")

                // Look at subsequent lines (below) for numbers
                for (j in (i + 1) until min(i + 5, allLines.size)) {
                    val belowLine = allLines[j]
                    val amount = extractAmountFromLine(belowLine.text)
                    if (amount != null) {
                        Timber.d("Found amount ${amount} below keyword at line $j: ${belowLine.text}")
                        return amount
                    }
                }

                // Also check the same line (in case number is on same line)
                val sameLineAmount = extractAmountFromLine(line.text)
                if (sameLineAmount != null) {
                    return sameLineAmount
                }
            }
        }

        return null
    }

    /**
     * Secondary strategy: Look for numbers near keyword (same/adjacent lines)
     */
    private fun extractAmountNearKeyword(text: Text, accountType: BalanceResult.AccountType): Double? {
        val keywords = when (accountType) {
            BalanceResult.AccountType.REAL -> qtRealPatterns
            BalanceResult.AccountType.DEMO -> qtDemoPatterns
            else -> return null
        }

        for (block in text.textBlocks) {
            for (line in block.lines) {
                val lineText = line.text.lowercase()
                val hasKeyword = keywords.any { lineText.contains(it.lowercase()) }

                if (hasKeyword) {
                    val amount = extractAmountFromLine(line.text)
                    if (amount != null) {
                        return amount
                    }
                }
            }
        }
        return null
    }

    private fun extractAmountFromLine(lineText: String): Double? {
        for (pattern in amountPatterns) {
            val matches = pattern.findAll(lineText)
            for (match in matches) {
                val amountStr = match.groupValues[1].replace(",", "").replace(" ", "")
                return try {
                    amountStr.toDouble()
                } catch (e: NumberFormatException) {
                    null
                }
            }
        }
        return null
    }

    private fun extractAnyAmount(text: String): Double? {
        for (pattern in amountPatterns) {
            val matches = pattern.findAll(text)
            val amounts = mutableListOf<Double>()
            for (match in matches) {
                val amountStr = match.groupValues[1].replace(",", "").replace(" ", "")
                try {
                    amounts.add(amountStr.toDouble())
                } catch (e: NumberFormatException) {
                    // Ignore
                }
            }
            if (amounts.isNotEmpty()) {
                return amounts.maxOrNull()
            }
        }
        return null
    }

    private fun calculateConfidence(text: Text, accountType: BalanceResult.AccountType, amount: Double): Float {
        var confidence = 0.5f

        val fullText = text.text.lowercase()
        val keywords = when (accountType) {
            BalanceResult.AccountType.REAL -> qtRealPatterns
            BalanceResult.AccountType.DEMO -> qtDemoPatterns
            else -> emptyList()
        }

        val keywordFound = keywords.any { fullText.contains(it.lowercase()) }
        if (keywordFound) confidence += 0.3f

        if (amount >= 1.0 && amount <= 1000000.0) {
            confidence += 0.1f
        }

        if (text.textBlocks.size > 1) {
            confidence += 0.1f
        }

        return minOf(confidence, 1.0f)
    }

    /**
     * Helper class to track line position for "below" detection
     */
    private data class LineWithPosition(
        val blockIndex: Int,
        val lineIndex: Int,
        val text: String,
        val boundingBox: android.graphics.Rect?
    )
}