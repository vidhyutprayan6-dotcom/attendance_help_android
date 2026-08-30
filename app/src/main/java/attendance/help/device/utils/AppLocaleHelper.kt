package attendance.help.device.utils

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/** Applies and persists app UI language (English / Arabic). */
object AppLocaleHelper {

    private val supportedTags = setOf("en", "ar")

    /** Apply when the user picks a language on the welcome screen (never during Application.onCreate). */
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
