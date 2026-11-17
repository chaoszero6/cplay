package com.chaoswares.chaosplay.ui.settings

import android.app.Application
import android.os.Build
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chaoswares.chaosplay.data.PinRules
import com.chaoswares.chaosplay.data.SettingsStore
import com.chaoswares.chaosplay.i18n.AppLanguage
import com.chaoswares.chaosplay.player.DecoderMode
import com.chaoswares.chaosplay.player.LibraryRules
import com.chaoswares.chaosplay.player.PlayerPrefs
import com.chaoswares.chaosplay.subtitles.SubtitleEncoding
import com.chaoswares.chaosplay.subtitles.SubtitleStyle
import com.chaoswares.chaosplay.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** Everything the Settings screen shows, spec 4.8. */
data class AppSettings(
    val loaded: Boolean = false,
    val theme: ThemeMode = ThemeMode.DARK,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val decoder: DecoderMode = DecoderMode.HW,
    val sortOrder: LibraryRules.SortOrder = LibraryRules.SortOrder.DATE_ADDED_DESC,
    val preferredSubtitle: String = "en",
    val preferredAudio: String = "en",
    val fallbackOrder: String = "ta,en,off",
    val subtitleEncoding: SubtitleEncoding = SubtitleEncoding.AUTO,
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    val player: PlayerPrefs = PlayerPrefs(),
    val pinSet: Boolean = false,
    val allFilesAccess: Boolean = false,
    val onboardingDone: Boolean = true
)

/**
 * Settings state holder: loads once, writes straight through so a choice
 * survives a restart. Owned by the activity so the theme applies app-wide.
 */
class AppSettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app)
    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    private var failedPinAttempts = 0
    private var lastPinFailureAt = 0L

    init {
        viewModelScope.launch {
            _state.value = AppSettings(
                loaded = true,
                theme = runCatching { ThemeMode.valueOf(store.themeMode.first()) }.getOrDefault(ThemeMode.DARK),
                language = AppLanguage.fromTag(store.appLanguage.first()),
                decoder = runCatching { DecoderMode.valueOf(store.decoderMode.first()) }.getOrDefault(DecoderMode.HW),
                sortOrder = runCatching { LibraryRules.SortOrder.valueOf(store.sortOrder.first()) }
                    .getOrDefault(LibraryRules.SortOrder.DATE_ADDED_DESC),
                preferredSubtitle = store.preferredSubtitleLanguage.first(),
                preferredAudio = store.preferredAudioLanguage.first(),
                fallbackOrder = store.subtitleFallbackOrder.first(),
                subtitleEncoding = runCatching { SubtitleEncoding.valueOf(store.subtitleEncoding.first()) }
                    .getOrDefault(SubtitleEncoding.AUTO),
                subtitleStyle = SubtitleStyle.decode(store.subtitleStyleRaw.first()),
                player = PlayerPrefs.decode(store.playerPrefsRaw.first()),
                pinSet = store.privacyPin.first().isNotBlank(),
                allFilesAccess = hasAllFilesAccess(),
                onboardingDone = store.onboardingDone.first()
            )
        }
    }

    fun refreshPermissions() = _state.update { it.copy(allFilesAccess = hasAllFilesAccess()) }

    private fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

    fun setTheme(v: ThemeMode) = save({ it.copy(theme = v) }) { store.setThemeMode(v.name) }
    fun setLanguage(v: AppLanguage) = save({ it.copy(language = v) }) { store.setAppLanguage(v.tag) }
    fun setDecoder(v: DecoderMode) = save({ it.copy(decoder = v) }) { store.setDecoderMode(v.name) }
    fun setSortOrder(v: LibraryRules.SortOrder) = save({ it.copy(sortOrder = v) }) { store.setSortOrder(v.name) }
    fun setPreferredSubtitle(v: String) = save({ it.copy(preferredSubtitle = v) }) { store.setPreferredSubtitleLanguage(v) }
    fun setPreferredAudio(v: String) = save({ it.copy(preferredAudio = v) }) { store.setPreferredAudioLanguage(v) }
    fun setFallbackOrder(v: String) = save({ it.copy(fallbackOrder = v) }) { store.setSubtitleFallbackOrder(v) }
    fun setSubtitleEncoding(v: SubtitleEncoding) = save({ it.copy(subtitleEncoding = v) }) { store.setSubtitleEncoding(v.name) }
    fun setSubtitleStyle(v: SubtitleStyle) = save({ it.copy(subtitleStyle = v) }) { store.setSubtitleStyleRaw(SubtitleStyle.encode(v)) }

    fun updatePlayer(transform: (PlayerPrefs) -> PlayerPrefs) {
        val next = PlayerPrefs.clamp(transform(_state.value.player))
        save({ it.copy(player = next) }) { store.setPlayerPrefsRaw(PlayerPrefs.encode(next)) }
    }

    private fun save(local: (AppSettings) -> AppSettings, persist: suspend () -> Unit) {
        _state.update(local)
        viewModelScope.launch { persist() }
    }

    // ---- privacy PIN (spec 7.5 privacy folder) ----

    fun setPin(pin: String): Boolean {
        if (!PinRules.isValid(pin)) return false
        val stored = PinRules.encode(pin, UUID.randomUUID().toString())
        save({ it.copy(pinSet = true) }) { store.setPrivacyPin(stored) }
        return true
    }

    sealed class PinCheck {
        object Ok : PinCheck()
        data class Wrong(val attemptsLeft: Int) : PinCheck()
        data class LockedOut(val seconds: Long) : PinCheck()
    }

    suspend fun checkPin(pin: String): PinCheck {
        val now = System.currentTimeMillis()
        val lock = PinRules.lockoutRemainingMs(failedPinAttempts, lastPinFailureAt, now)
        if (lock > 0) return PinCheck.LockedOut((lock + 999) / 1000)
        if (failedPinAttempts >= PinRules.MAX_ATTEMPTS) failedPinAttempts = 0
        val ok = PinRules.verify(pin, store.privacyPin.first())
        return if (ok) {
            failedPinAttempts = 0
            PinCheck.Ok
        } else {
            failedPinAttempts++
            lastPinFailureAt = now
            PinCheck.Wrong((PinRules.MAX_ATTEMPTS - failedPinAttempts).coerceAtLeast(0))
        }
    }

    fun clearPin() = save({ it.copy(pinSet = false) }) { store.setPrivacyPin("") }

    /** Finishes the welcome flow with the choices made in quick setup. */
    fun completeOnboarding(subtitleLanguage: String, theme: ThemeMode) {
        setPreferredSubtitle(subtitleLanguage)
        setTheme(theme)
        save({ it.copy(onboardingDone = true) }) { store.setOnboardingDone(true) }
    }

    fun restartOnboarding() = save({ it.copy(onboardingDone = false) }) { store.setOnboardingDone(false) }

    fun clearHistory() {
        viewModelScope.launch { store.setResumeRaw("") }
    }
}
