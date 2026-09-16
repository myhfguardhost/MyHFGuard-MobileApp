package com.vitalink.app.util

/** Three meals: natural = 1, moderate = 2, high = 3. */
object SaltScore {
    fun display(stored: Int): Int = stored.coerceIn(3, 9)
    fun status(stored: Int): String = when (display(stored)) {
        in 3..5 -> "green"
        6 -> "orange"
        else -> "red"
    }
    fun label(stored: Int): String = when (status(stored)) {
        "green" -> AppLanguage.text("Natural", "Semula jadi", "清淡", "இயற்கை")
        "orange" -> AppLanguage.text("Moderate", "Sederhana", "适中", "மிதமான")
        else -> AppLanguage.text("High", "Tinggi", "高盐", "அதிகம்")
    }
}
