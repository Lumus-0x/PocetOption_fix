package com.pocet.screenmonitor.model

import androidx.annotation.Keep

@Keep
data class BalanceResult(
    val accountType: AccountType,
    val amount: Double,
    val confidence: Float,
    val rawText: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class AccountType {
        REAL,
        DEMO,
        UNKNOWN
    }

    companion object {
        fun unknown(rawText: String): BalanceResult {
            return BalanceResult(AccountType.UNKNOWN, 0.0, 0.0f, rawText)
        }
    }

    fun isValid(): Boolean {
        return accountType != AccountType.UNKNOWN && amount > 0 && confidence > 0.5f
    }
}