package attendance.help.device.utils

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * App language (English / Arabic) with SharedPreferences + AppCompat persistence.
 *
 * Do not call [AppCompatDelegate.setApplicationLocales] from [android.app.Application.onCreate]
 * (causes startup ANR). Apply via [syncDelegateWithStoredPreference] in activity
 * [android.app.Activity.attachBaseContext] instead.
 */
object AppLocaleHelper {

    private const val PREFS_NAME = "ah_app_locale"
    private const val KEY_TAG = "language_tag"
    private val supportedTags = setOf("en", "ar")

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Restore saved language before the activity context is created.
     * Safe to call from [android.app.Activity.attachBaseContext].
     */
    fun syncDelegateWithStoredPreference(context: Context) {
        val tag = prefs(context).getString(KEY_TAG, null) ?: return
        if (tag !in supportedTags) return
        val applied = AppCompatDelegate.getApplicationLocales()
        val appliedTag = if (!applied.isEmpty) {
            applied[0]?.language?.lowercase(Locale.ROOT)
        } else {
            null
        }
        if (appliedTag != tag) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        }
    }

    /** User chose a language — persist and apply (activity recreates unless locale is in configChanges). */
    fun setLanguage(context: Context, languageTag: String) {
        val tag = if (languageTag in supportedTags) languageTag else "en"
        prefs(context).edit().putString(KEY_TAG, tag).apply()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    fun hasSavedLanguage(context: Context): Boolean =
        prefs(context).getString(KEY_TAG, null) in supportedTags

    /** Language tag for UI highlighting: saved preference, else device language, else English. */
    fun currentLanguageTag(context: Context): String {
        prefs(context).getString(KEY_TAG, null)?.let { if (it in supportedTags) return it }
        val stored = AppCompatDelegate.getApplicationLocales()
        if (!stored.isEmpty) {
            val lang = stored[0]?.language?.lowercase(Locale.ROOT).orEmpty()
            if (lang in supportedTags) return lang
        }
        val deviceLang = Locale.getDefault().language.lowercase(Locale.ROOT)
        return if (deviceLang in supportedTags) deviceLang else "en"
    }
}
