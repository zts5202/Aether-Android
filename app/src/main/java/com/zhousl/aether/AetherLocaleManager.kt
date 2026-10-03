package com.zhousl.aether

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.zhousl.aether.data.AppLanguage
import com.zhousl.aether.data.appLanguageForTag
import com.zhousl.aether.data.defaultAppLanguage

object AetherLocaleManager {
    fun apply(language: AppLanguage) {
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(language.languageTag),
        )
    }

    fun applyIfChanged(language: AppLanguage) {
        if (currentApplicationLanguage() == language) return
        apply(language)
    }

    fun currentApplicationLanguage(): AppLanguage? =
        AppCompatDelegate.getApplicationLocales().get(0)
            ?.toLanguageTag()
            ?.let(::appLanguageForTag)

    fun currentLanguage(): AppLanguage =
        currentApplicationLanguage() ?: defaultAppLanguage()
}
