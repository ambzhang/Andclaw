package com.andforce.andclaw.settings

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

object LanguageHelper {

    fun setLocale(context: Context, languageCode: String): Context {
        val locale = when (languageCode) {
            "zh" -> Locale.SIMPLIFIED_CHINESE
            "en" -> Locale.ENGLISH
            else -> Locale.getDefault()
        }
        
        Locale.setDefault(locale)
        
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        
        return context.createConfigurationContext(config)
    }

    fun getCurrentLanguage(context: Context): String {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        return prefs.getString("language", "system") ?: "system"
    }

    fun applyLanguage(context: Context) {
        val language = getCurrentLanguage(context)
        if (language != "system") {
            setLocale(context, language)
        }
    }
}