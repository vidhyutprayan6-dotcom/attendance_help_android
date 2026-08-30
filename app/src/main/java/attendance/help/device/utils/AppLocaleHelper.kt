package attendance.help.device.utils

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/** Applies and persists app UI language (English / Arabic). */
object AppLocaleHelper {

    private val supportedTags = setOf("en", "ar")

    /**
     * Call from [android.app.Application.onCreate] before any UI is shown.
     * Restores a saved locale, or follows the device language on first launch.
     */
    fun syncOnLaunch() {
        val stored = AppCompatDelegate.getApplicationLocales()
        if (!stored.isEmpty) return

        val deviceLang = Locale.getDefault().language.lowercase(Locale.ROOT)
        val initialTag = if (deviceLang in supportedTags) deviceLang else "en"
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(initialTag))
    }

    fun setLanguage(languageTag: String) {
        val tag = if (languageTag in supportedTags) languageTag else "en"
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    /** Current app language tag: `en` or `ar`. */
    fun currentLanguageTag(): String {
        val stored = AppCompatDelegate.getApplicationLocales()
        if (!stored.isEmpty) {
            val tag = stored[0]?.toLanguageTag().orEmpty()
            val lang = tag.substringBefore("-").lowercase(Locale.ROOT)
            if (lang in supportedTags) return lang
        }
        val deviceLang = Locale.getDefault().language.lowercase(Locale.ROOT)
        return if (deviceLang in supportedTags) deviceLang else "en"
    }
}
