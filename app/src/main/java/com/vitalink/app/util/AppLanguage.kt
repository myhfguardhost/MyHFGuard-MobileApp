package com.vitalink.app.util

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class AppLanguageCode(val storageValue: String) {
    ENGLISH("en"),
    MALAY("ms"),
    MANDARIN("zh"),
    TAMIL("ta")
}

object AppLanguage {
    private const val PREFS = "myhfguard_language"
    private const val KEY_LANGUAGE = "selected_language"

    var current by mutableStateOf(AppLanguageCode.ENGLISH)
        private set

    val useMalay: Boolean get() = current == AppLanguageCode.MALAY
    val useMandarin: Boolean get() = current == AppLanguageCode.MANDARIN
    val useTamil: Boolean get() = current == AppLanguageCode.TAMIL

    fun initialize(context: Context) {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, AppLanguageCode.ENGLISH.storageValue)
        current = AppLanguageCode.entries.firstOrNull { it.storageValue == stored }
            ?: AppLanguageCode.ENGLISH
    }

    fun setLanguage(context: Context, language: AppLanguageCode) {
        current = language
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, language.storageValue)
            .apply()
    }

    fun text(
        en: String,
        ms: String,
        zh: String? = null,
        ta: String? = null
    ): String = when (current) {
        AppLanguageCode.ENGLISH -> en
        AppLanguageCode.MALAY -> ms
        AppLanguageCode.MANDARIN -> zh ?: TranslationCatalog.translate(en, AppLanguageCode.MANDARIN)
        AppLanguageCode.TAMIL -> ta ?: TranslationCatalog.translate(en, AppLanguageCode.TAMIL)
    }

    fun translate(en: String): String = when (current) {
        AppLanguageCode.ENGLISH -> en
        AppLanguageCode.MALAY -> en
        AppLanguageCode.MANDARIN -> TranslationCatalog.translate(en, AppLanguageCode.MANDARIN)
        AppLanguageCode.TAMIL -> TranslationCatalog.translate(en, AppLanguageCode.TAMIL)
    }
}
