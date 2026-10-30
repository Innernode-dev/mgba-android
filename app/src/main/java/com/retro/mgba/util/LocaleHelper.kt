package com.retro.mgba.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.retro.mgba.SettingsActivity
import java.util.Locale

object LocaleHelper {
    const val KEY_LANGUAGE = "app_language"

    val SUPPORTED_LANGUAGES = listOf(
        "" to "Автоматически (По языку телефона)",
        "ru" to "Русский (Russian)",
        "en" to "English",
        "es" to "Español (Spanish)",
        "pt" to "Português (Portuguese)",
        "fr" to "Français (French)",
        "de" to "Deutsch (German)",
        "it" to "Italiano (Italian)",
        "ja" to "日本語 (Japanese)",
        "zh" to "简体中文 (Chinese)",
        "ko" to "한국어 (Korean)",
        "uk" to "Українська (Ukrainian)",
        "pl" to "Polski (Polish)",
        "nl" to "Nederlands (Dutch)",
        "sv" to "Svenska (Swedish)",
        "tr" to "Türkçe (Turkish)",
        "ar" to "العربية (Arabic)",
        "hi" to "हिन्दी (Hindi)",
        "th" to "ไทย (Thai)",
        "vi" to "Tiếng Việt (Vietnamese)",
        "id" to "Bahasa Indonesia (Indonesian)",
        "cs" to "Čeština (Czech)",
        "el" to "Ελληνικά (Greek)"
    )

    fun getSelectedLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANGUAGE, "") ?: ""
    }

    fun getLanguageDisplayLabel(context: Context): String {
        val currentCode = getSelectedLanguage(context)
        if (currentCode.isEmpty()) {
            val systemLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.resources.configuration.locales.get(0) ?: Locale.getDefault()
            } else {
                @Suppress("DEPRECATION")
                context.resources.configuration.locale ?: Locale.getDefault()
            }
            val sysName = systemLocale.getDisplayLanguage(systemLocale).replaceFirstChar { it.uppercase() }
            return "Авто ($sysName)"
        }
        return SUPPORTED_LANGUAGES.firstOrNull { it.first == currentCode }?.second
            ?: "Авто (По языку телефона)"
    }

    fun setLanguage(context: Context, langCode: String) {
        val prefs = context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, langCode).apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val localeManager = context.getSystemService(android.app.LocaleManager::class.java)
                if (langCode.isNotEmpty()) {
                    localeManager?.applicationLocales = LocaleList(Locale.forLanguageTag(langCode))
                } else {
                    localeManager?.applicationLocales = LocaleList.getEmptyLocaleList()
                }
            } catch (_: Exception) {}
        }
    }

    fun applyLocale(context: Context): Context {
        val lang = getSelectedLanguage(context)
        if (lang.isEmpty()) return context

        val locale = Locale.forLanguageTag(lang)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
