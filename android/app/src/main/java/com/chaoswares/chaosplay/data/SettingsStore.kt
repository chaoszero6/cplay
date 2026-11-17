package com.chaoswares.chaosplay.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "chaosplay_settings")

/**
 * Persisted settings and per-video resume positions (spec section 4.8 and
 * "Resume from the last position per file").
 *
 * Resume positions are stored as "uri|positionMs|durationMs|timestamp|playCount"
 * lines so the format stays inspectable and testable. List settings (favourites,
 * hidden files, playlists) live in [LibraryMetaStore].
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val PREFERRED_SUBTITLE_LANG = stringPreferencesKey("preferred_subtitle_language")
        val PREFERRED_AUDIO_LANG = stringPreferencesKey("preferred_audio_language")
        val SUBTITLE_FALLBACK = stringPreferencesKey("subtitle_fallback_order")
        val DECODER_MODE = stringPreferencesKey("decoder_mode")
        val SORT_ORDER = stringPreferencesKey("library_sort_order")
        val VIEW_MODE = stringPreferencesKey("library_view_mode")
        val RESUME = stringPreferencesKey("resume_positions")
        val GESTURE_SETTINGS = stringPreferencesKey("gesture_settings")
        val PLAYER_PREFS = stringPreferencesKey("player_prefs")
        val SUBTITLE_STYLE = stringPreferencesKey("subtitle_style")
        val SUBTITLE_ENCODING = stringPreferencesKey("subtitle_encoding")
        val SUBTITLE_CHOICES = stringPreferencesKey("subtitle_choices")
        val BOOKMARKS = stringPreferencesKey("bookmarks")
        val PRIVACY_PIN = stringPreferencesKey("privacy_pin")
        val EQ_PRESET = stringPreferencesKey("eq_preset")
        val ONBOARDING_DONE = stringPreferencesKey("onboarding_done")
    }

    val themeMode: Flow<String> = context.dataStore.data
        .map { it[Keys.THEME] ?: "DARK" }

    val appLanguage: Flow<String> = context.dataStore.data
        .map { it[Keys.APP_LANGUAGE] ?: "" }

    val preferredSubtitleLanguage: Flow<String> = context.dataStore.data
        .map { it[Keys.PREFERRED_SUBTITLE_LANG] ?: "en" }

    val preferredAudioLanguage: Flow<String> = context.dataStore.data
        .map { it[Keys.PREFERRED_AUDIO_LANG] ?: "en" }

    val subtitleFallbackOrder: Flow<String> = context.dataStore.data
        .map { it[Keys.SUBTITLE_FALLBACK] ?: "ta,en,off" }

    val decoderMode: Flow<String> = context.dataStore.data
        .map { it[Keys.DECODER_MODE] ?: "HW" }

    val sortOrder: Flow<String> = context.dataStore.data
        .map { it[Keys.SORT_ORDER] ?: "DATE_ADDED_DESC" }

    val viewMode: Flow<String> = context.dataStore.data
        .map { it[Keys.VIEW_MODE] ?: "GRID" }

    val resumeRaw: Flow<String> = context.dataStore.data
        .map { it[Keys.RESUME] ?: "" }

    val gestureSettingsRaw: Flow<String> = context.dataStore.data
        .map { it[Keys.GESTURE_SETTINGS] ?: "" }

    val playerPrefsRaw: Flow<String> = context.dataStore.data.map { it[Keys.PLAYER_PREFS] ?: "" }
    val subtitleStyleRaw: Flow<String> = context.dataStore.data.map { it[Keys.SUBTITLE_STYLE] ?: "" }
    val subtitleEncoding: Flow<String> = context.dataStore.data.map { it[Keys.SUBTITLE_ENCODING] ?: "AUTO" }
    val subtitleChoicesRaw: Flow<String> = context.dataStore.data.map { it[Keys.SUBTITLE_CHOICES] ?: "" }
    val bookmarksRaw: Flow<String> = context.dataStore.data.map { it[Keys.BOOKMARKS] ?: "" }
    val privacyPin: Flow<String> = context.dataStore.data.map { it[Keys.PRIVACY_PIN] ?: "" }
    val eqPreset: Flow<String> = context.dataStore.data.map { it[Keys.EQ_PRESET] ?: "FLAT" }

    suspend fun setPlayerPrefsRaw(value: String) = put(Keys.PLAYER_PREFS, value)
    suspend fun setSubtitleStyleRaw(value: String) = put(Keys.SUBTITLE_STYLE, value)
    suspend fun setSubtitleEncoding(value: String) = put(Keys.SUBTITLE_ENCODING, value)
    suspend fun setPrivacyPin(value: String) = put(Keys.PRIVACY_PIN, value)
    suspend fun setEqPreset(value: String) = put(Keys.EQ_PRESET, value)

    /** First-launch welcome finished (spec 5 Flow E). */
    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[Keys.ONBOARDING_DONE] == "true" }
    suspend fun setOnboardingDone(done: Boolean) = put(Keys.ONBOARDING_DONE, done.toString())

    /** Read-modify-write in one DataStore transaction, so two quick saves never lose one. */
    suspend fun updateSubtitleChoices(transform: (String) -> String) {
        context.dataStore.edit { prefs -> prefs[Keys.SUBTITLE_CHOICES] = transform(prefs[Keys.SUBTITLE_CHOICES] ?: "") }
    }

    suspend fun updateBookmarks(transform: (String) -> String) {
        context.dataStore.edit { prefs -> prefs[Keys.BOOKMARKS] = transform(prefs[Keys.BOOKMARKS] ?: "") }
    }

    suspend fun updateResume(transform: (String) -> String) {
        context.dataStore.edit { prefs -> prefs[Keys.RESUME] = transform(prefs[Keys.RESUME] ?: "") }
    }

    suspend fun setThemeMode(value: String) = put(Keys.THEME, value)
    suspend fun setAppLanguage(value: String) = put(Keys.APP_LANGUAGE, value)
    suspend fun setPreferredSubtitleLanguage(value: String) =
        put(Keys.PREFERRED_SUBTITLE_LANG, value)

    suspend fun setPreferredAudioLanguage(value: String) =
        put(Keys.PREFERRED_AUDIO_LANG, value)

    suspend fun setSubtitleFallbackOrder(value: String) = put(Keys.SUBTITLE_FALLBACK, value)
    suspend fun setDecoderMode(value: String) = put(Keys.DECODER_MODE, value)
    suspend fun setSortOrder(value: String) = put(Keys.SORT_ORDER, value)
    suspend fun setViewMode(value: String) = put(Keys.VIEW_MODE, value)
    suspend fun setResumeRaw(value: String) = put(Keys.RESUME, value)
    suspend fun setGestureSettingsRaw(value: String) = put(Keys.GESTURE_SETTINGS, value)

    private suspend fun put(key: androidx.datastore.preferences.core.Preferences.Key<String>, value: String) {
        context.dataStore.edit { prefs -> prefs[key] = value }
    }
}

/**
 * Serialisation for the resume table. Kept pure so it is unit testable
 * without DataStore or Android.
 */
object ResumeCodec {

    private const val FIELD_SEP = "|"
    private const val ENTRY_SEP = "\n"

    fun encode(entries: List<com.chaoswares.chaosplay.player.WatchEntry>): String {
        return entries.joinToString(ENTRY_SEP) { entry ->
            listOf(
                entry.uri,
                entry.positionMs.toString(),
                entry.durationMs.toString(),
                entry.lastPlayedAtSec.toString(),
                entry.playCount.toString()
            ).joinToString(FIELD_SEP)
        }
    }

    fun decode(raw: String): List<com.chaoswares.chaosplay.player.WatchEntry> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .mapNotNull { line ->
                val parts = line.split(FIELD_SEP)
                if (parts.size < 5) return@mapNotNull null
                val position = parts[1].toLongOrNull() ?: return@mapNotNull null
                val duration = parts[2].toLongOrNull() ?: return@mapNotNull null
                val timestamp = parts[3].toLongOrNull() ?: return@mapNotNull null
                val playCount = parts[4].toIntOrNull() ?: 0
                com.chaoswares.chaosplay.player.WatchEntry(
                    uri = parts[0],
                    positionMs = position,
                    durationMs = duration,
                    lastPlayedAtSec = timestamp,
                    playCount = playCount
                )
            }
            .toList()
    }

    /** Inserts or replaces an entry, keeping the table bounded. */
    fun upsert(
        entries: List<com.chaoswares.chaosplay.player.WatchEntry>,
        entry: com.chaoswares.chaosplay.player.WatchEntry,
        maxEntries: Int = 500
    ): List<com.chaoswares.chaosplay.player.WatchEntry> {
        val without = entries.filterNot { it.uri == entry.uri }
        return (listOf(entry) + without)
            .sortedByDescending { it.lastPlayedAtSec }
            .take(maxEntries)
    }
}
