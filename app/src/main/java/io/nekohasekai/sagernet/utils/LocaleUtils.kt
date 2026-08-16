package io.nekohasekai.sagernet.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale

object LocaleUtils {

    private const val PREF_NAME = "locale"
    private const val KEY_LANGUAGE = "appLanguage"
    const val LANGUAGE_SYSTEM = "system"
    const val LANGUAGE_ZH_CN = "zh-CN"
    const val LANGUAGE_EN = "en"

    fun wrap(context: Context): Context {
        val language = getLanguage(context)
        if (language == LANGUAGE_SYSTEM) return context

        val appLocale = Locale.forLanguageTag(language)
        Locale.setDefault(appLocale)

        val config = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val locales = LocaleList(appLocale)
            LocaleList.setDefault(locales)
            config.setLocales(locales)
        } else {
            @Suppress("DEPRECATION")
            config.locale = appLocale
        }

        return context.createConfigurationContext(config)
    }

    fun persist(context: Context, language: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_LANGUAGE, language) }
    }

    private fun getLanguage(context: Context): String {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, LANGUAGE_ZH_CN)
            ?: LANGUAGE_ZH_CN
    }
}
