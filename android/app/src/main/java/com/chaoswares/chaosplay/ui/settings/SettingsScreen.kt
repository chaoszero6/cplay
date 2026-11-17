package com.chaoswares.chaosplay.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.chaoswares.chaosplay.BuildConfig
import com.chaoswares.chaosplay.data.PinRules
import com.chaoswares.chaosplay.i18n.AppLanguage
import com.chaoswares.chaosplay.player.DecoderMode
import com.chaoswares.chaosplay.player.LibraryRules
import com.chaoswares.chaosplay.player.PlayerPrefs
import com.chaoswares.chaosplay.player.TimeFormat
import com.chaoswares.chaosplay.subtitles.SubtitleEncoding
import com.chaoswares.chaosplay.subtitles.SubtitleStyle
import com.chaoswares.chaosplay.ui.library.LibraryViewModel
import com.chaoswares.chaosplay.ui.network.NetworkViewModel
import com.chaoswares.chaosplay.ui.theme.ChaosSpacing
import com.chaoswares.chaosplay.ui.theme.ChaosType
import com.chaoswares.chaosplay.ui.theme.HeaderTitle
import com.chaoswares.chaosplay.ui.theme.ThemeMode
import com.chaoswares.chaosplay.ui.theme.chaosTokens
import kotlinx.coroutines.launch

private data class Picker(val title: String, val options: List<String>, val selected: Int, val onSelect: (Int) -> Unit)

private val LANGUAGES = listOf(
    "en" to "English", "ta" to "Tamil", "hi" to "Hindi", "te" to "Telugu", "ml" to "Malayalam",
    "kn" to "Kannada", "bn" to "Bengali", "mr" to "Marathi", "es" to "Spanish", "fr" to "French",
    "de" to "German", "ja" to "Japanese", "ko" to "Korean", "ar" to "Arabic"
)

private val FALLBACKS = listOf(
    "ta,en,off" to "Tamil, then English, then off",
    "en,off" to "English, then off",
    "hi,en,off" to "Hindi, then English, then off",
    "en" to "English, then any track",
    "off" to "Off unless preferred language exists"
)

/**
 * Settings, spec section 4.8. Every row is live: it changes behaviour and
 * is saved immediately.
 */
@Composable
fun SettingsScreen(
    viewModel: AppSettingsViewModel,
    libraryViewModel: LibraryViewModel,
    networkViewModel: NetworkViewModel
) {
    val tokens = chaosTokens()
    val s by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picker by remember { mutableStateOf<Picker?>(null) }
    var pinDialog by remember { mutableStateOf<String?>(null) } // "set" | "unlock"
    var hiddenOpen by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) viewModel.refreshPermissions() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val p = s.player
    fun lang(code: String) = LANGUAGES.firstOrNull { it.first == code }?.second ?: code

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.bg),
        contentPadding = PaddingValues(bottom = ChaosSpacing.XXL.dp)
    ) {
        item {
            HeaderTitle(
                "Settings",
                color = tokens.text,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(start = ChaosSpacing.L.dp, top = ChaosSpacing.L.dp, bottom = ChaosSpacing.S.dp)
            )
        }

        item { SectionLabel("General") }
        item {
            Row("Theme", s.theme.label) {
                picker = Picker("Theme", ThemeMode.entries.map { it.label }, s.theme.ordinal) { viewModel.setTheme(ThemeMode.entries[it]) }
            }
        }
        item {
            Row("App language", s.language.nativeName, hint = "Menus are in English for now; the choice is saved for when translations ship") {
                picker = Picker("App language", AppLanguage.entries.map { it.nativeName }, s.language.ordinal) {
                    viewModel.setLanguage(AppLanguage.entries[it])
                }
            }
        }

        item { SectionLabel("Playback") }
        item {
            Row("Decoder", "${s.decoder.label} (${s.decoder.shortLabel})") {
                picker = Picker("Decoder", DecoderMode.entries.map { "${it.label} (${it.shortLabel})" }, s.decoder.ordinal) {
                    viewModel.setDecoder(DecoderMode.entries[it])
                }
            }
        }
        item {
            Row("Default speed", TimeFormat.formatSpeed(p.defaultSpeed)) {
                val options = PlayerPrefs.DEFAULT_SPEED_OPTIONS
                picker = Picker("Default speed", options.map { TimeFormat.formatSpeed(it) }, options.indexOf(p.defaultSpeed)) { i ->
                    viewModel.updatePlayer { it.copy(defaultSpeed = options[i]) }
                }
            }
        }
        item {
            Row("Seek step (double tap, ⏪ ⏩)", PlayerPrefs.seekStepLabel(p.seekStepMs)) {
                val options = PlayerPrefs.SEEK_STEP_OPTIONS_MS
                picker = Picker("Seek step", options.map { PlayerPrefs.seekStepLabel(it) }, options.indexOf(p.seekStepMs)) { i ->
                    viewModel.updatePlayer { it.copy(seekStepMs = options[i]) }
                }
            }
        }
        item {
            Row("Hide controls after", "${p.autoHideMs / 1000} s") {
                val options = PlayerPrefs.AUTO_HIDE_OPTIONS_MS
                picker = Picker("Hide controls after", options.map { "${it / 1000} s" }, options.indexOf(p.autoHideMs)) { i ->
                    viewModel.updatePlayer { it.copy(autoHideMs = options[i]) }
                }
            }
        }
        item { Toggle("Resume where I left off", p.resumeEnabled) { v -> viewModel.updatePlayer { it.copy(resumeEnabled = v) } } }
        item { Toggle("Play the next video automatically", p.autoPlayNext) { v -> viewModel.updatePlayer { it.copy(autoPlayNext = v) } } }
        item {
            Toggle("Background play", p.backgroundPlay, "Keeps playing with a media notification when you leave the app") { v ->
                viewModel.updatePlayer { it.copy(backgroundPlay = v) }
            }
        }
        item {
            Toggle("Picture-in-picture on Home", p.autoPip, "Shrinks the playing video into a window when you leave") { v ->
                viewModel.updatePlayer { it.copy(autoPip = v) }
            }
        }
        item { Toggle("Landscape for wide videos", p.landscapeForWideVideo) { v -> viewModel.updatePlayer { it.copy(landscapeForWideVideo = v) } } }
        item { Toggle("Keep screen on while playing", p.keepScreenOn) { v -> viewModel.updatePlayer { it.copy(keepScreenOn = v) } } }

        item { SectionLabel("Subtitles") }
        item {
            Row("Preferred language", lang(s.preferredSubtitle)) {
                picker = Picker("Preferred subtitle language", LANGUAGES.map { it.second }, LANGUAGES.indexOfFirst { it.first == s.preferredSubtitle }) {
                    viewModel.setPreferredSubtitle(LANGUAGES[it].first)
                }
            }
        }
        item {
            Row("Fallback order", FALLBACKS.firstOrNull { it.first == s.fallbackOrder }?.second ?: s.fallbackOrder) {
                picker = Picker("When the preferred language is missing", FALLBACKS.map { it.second }, FALLBACKS.indexOfFirst { it.first == s.fallbackOrder }) {
                    viewModel.setFallbackOrder(FALLBACKS[it].first)
                }
            }
        }
        item {
            Row("Encoding", s.subtitleEncoding.label) {
                picker = Picker("Subtitle file encoding", SubtitleEncoding.entries.map { it.label }, s.subtitleEncoding.ordinal) {
                    viewModel.setSubtitleEncoding(SubtitleEncoding.entries[it])
                }
            }
        }
        item {
            Row("Text size", "${s.subtitleStyle.sizeSp}") {
                val options = SubtitleStyle.SIZE_PRESETS
                picker = Picker("Subtitle size", options.map { "$it" }, options.indexOf(s.subtitleStyle.sizeSp)) {
                    viewModel.setSubtitleStyle(s.subtitleStyle.copy(sizeSp = options[it]))
                }
            }
        }
        item {
            Row("Text colour", SubtitleStyle.COLOR_PRESETS.firstOrNull { it.second == s.subtitleStyle.colorArgb }?.first ?: "Custom") {
                val options = SubtitleStyle.COLOR_PRESETS
                picker = Picker("Subtitle colour", options.map { it.first }, options.indexOfFirst { it.second == s.subtitleStyle.colorArgb }) {
                    viewModel.setSubtitleStyle(s.subtitleStyle.copy(colorArgb = options[it].second))
                }
            }
        }
        item { Toggle("Background box behind text", s.subtitleStyle.background) { v -> viewModel.setSubtitleStyle(s.subtitleStyle.copy(background = v)) } }
        item {
            val needs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            Row(
                "Load subtitle files next to videos",
                when {
                    !needs -> "On"
                    s.allFilesAccess -> "Allowed"
                    else -> "Needs All files access"
                },
                hint = "Android only lets apps read .srt/.ass files from other apps with All files access. You can always add a file from the subtitle menu instead."
            ) {
                if (needs) {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                    runCatching { context.startActivity(intent) }.onFailure {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
            }
        }

        item { SectionLabel("Audio") }
        item {
            Row("Preferred audio language", lang(s.preferredAudio)) {
                picker = Picker("Preferred audio language", LANGUAGES.map { it.second }, LANGUAGES.indexOfFirst { it.first == s.preferredAudio }) {
                    viewModel.setPreferredAudio(LANGUAGES[it].first)
                }
            }
        }

        item {
            Toggle("Volume levelling", p.volumeLevelling, "Evens out quiet dialogue and loud scenes (Android 9+)") { v ->
                viewModel.updatePlayer { it.copy(volumeLevelling = v) }
            }
        }

        item { SectionLabel("Gestures") }
        val g = p.gestures
        item { Toggle("Brightness (drag left side)", g.brightnessEnabled) { v -> viewModel.updatePlayer { it.copy(gestures = it.gestures.copy(brightnessEnabled = v)) } } }
        item { Toggle("Volume and boost (drag right side)", g.volumeEnabled) { v -> viewModel.updatePlayer { it.copy(gestures = it.gestures.copy(volumeEnabled = v)) } } }
        item { Toggle("Seek (drag sideways)", g.seekEnabled) { v -> viewModel.updatePlayer { it.copy(gestures = it.gestures.copy(seekEnabled = v)) } } }
        item { Toggle("Double tap sides to skip", g.doubleTapSkipEnabled) { v -> viewModel.updatePlayer { it.copy(gestures = it.gestures.copy(doubleTapSkipEnabled = v)) } } }
        item { Toggle("Pinch to zoom", g.pinchZoomEnabled) { v -> viewModel.updatePlayer { it.copy(gestures = it.gestures.copy(pinchZoomEnabled = v)) } } }
        item { Toggle("Two-finger swipe for speed", g.twoFingerSpeedEnabled) { v -> viewModel.updatePlayer { it.copy(gestures = it.gestures.copy(twoFingerSpeedEnabled = v)) } } }
        item { Toggle("Hold for 2x speed", g.longPressSpeedEnabled) { v -> viewModel.updatePlayer { it.copy(gestures = it.gestures.copy(longPressSpeedEnabled = v)) } } }
        item {
            Toggle("Swipe down in the middle for picture-in-picture", g.swipeDownPipEnabled, "Uses the centre strip, so brightness/volume move to the outer thirds") { v ->
                viewModel.updatePlayer { it.copy(gestures = it.gestures.copy(swipeDownPipEnabled = v)) }
            }
        }

        item { Toggle("Vibrate on gestures", p.haptics, "Light feedback on skips, hold for 2x and the kids lock") { v -> viewModel.updatePlayer { it.copy(haptics = v) } } }
        item { Toggle("Kids lock touch effects", p.kidsTouchEffects, "Colourful ripples where small fingers tap while locked") { v -> viewModel.updatePlayer { it.copy(kidsTouchEffects = v) } } }
        item { Row("Show welcome again", "") { viewModel.restartOnboarding() } }

        item { SectionLabel("Library") }
        item {
            Row("Sort order", s.sortOrder.label) {
                picker = Picker("Sort order", LibraryRules.SortOrder.entries.map { it.label }, s.sortOrder.ordinal) {
                    viewModel.setSortOrder(LibraryRules.SortOrder.entries[it])
                    libraryViewModel.setSortOrder(LibraryRules.SortOrder.entries[it])
                }
            }
        }
        item {
            val hiddenCount = libraryViewModel.state.collectAsState().value.hiddenIds.size
            Row("Hidden videos (privacy)", if (s.pinSet) "$hiddenCount · PIN protected" else "$hiddenCount · no PIN") {
                pinDialog = if (s.pinSet) "unlock" else "set"
            }
        }
        if (s.pinSet) {
            item { Row("Remove privacy PIN", "") { pinDialog = "remove" } }
        }
        item {
            Row("Clear watch history", "Resume points too") {
                confirm = "Clear watch history and resume points? Video files are not touched." to {
                    viewModel.clearHistory()
                    libraryViewModel.refresh()
                }
            }
        }

        item { SectionLabel("Network") }
        item {
            Row("Clear saved servers and passwords", "") {
                confirm = "Remove every saved server, stream and stored password?" to { networkViewModel.clearAll() }
            }
        }

        item { SectionLabel("About") }
        item { Row("Version", BuildConfig.VERSION_NAME, chevron = false) {} }
        item {
            Text(
                text = "CPlay is free, open-source software under the GNU GPL v3: no ads, no tracking. " +
                    "Source code: github.com/chaoszero6/cplay. Uses Media3 and OkHttp (Apache-2.0), the Jellyfin FFmpeg " +
                    "decoder for AC3/DTS (GPL-3.0, FFmpeg), smbj (Apache-2.0), dcerpc (BSD-3-Clause), Apache Commons Net, " +
                    "JSch (BSD) and nfs-client (Apache-2.0). Server passwords and tokens are encrypted with the Android Keystore.",
                style = ChaosType.Caption,
                color = tokens.textMuted,
                modifier = Modifier.padding(ChaosSpacing.L.dp)
            )
        }
    }

    picker?.let { pk ->
        PickerSheet(pk.title, pk.options, pk.selected, onSelect = { pk.onSelect(it); picker = null }, onDismiss = { picker = null })
    }

    confirm?.let { (message, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = tokens.surface,
            text = { Text(message, color = tokens.text) },
            confirmButton = { TextButton(onClick = { action(); confirm = null }) { Text("Confirm", color = tokens.danger) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
        )
    }

    pinDialog?.let { mode ->
        PinDialog(
            mode = mode,
            onSubmit = { pin, done ->
                when (mode) {
                    "set" -> if (viewModel.setPin(pin)) { done(null); hiddenOpen = true } else done("Use 4 to 8 digits")
                    else -> scope.launch {
                        when (val r = viewModel.checkPin(pin)) {
                            AppSettingsViewModel.PinCheck.Ok -> {
                                done(null)
                                if (mode == "remove") viewModel.clearPin() else hiddenOpen = true
                            }
                            is AppSettingsViewModel.PinCheck.Wrong -> done("Wrong PIN · ${r.attemptsLeft} tries left")
                            is AppSettingsViewModel.PinCheck.LockedOut -> done("Too many tries. Wait ${r.seconds} s")
                        }
                    }
                }
            },
            onDismiss = { pinDialog = null },
            onClose = { pinDialog = null }
        )
    }

    if (hiddenOpen) {
        HiddenVideosSheet(libraryViewModel = libraryViewModel, onDismiss = { hiddenOpen = false })
    }
}

@Composable
private fun PinDialog(
    mode: String,
    onSubmit: (String, (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onClose: () -> Unit
) {
    val tokens = chaosTokens()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = tokens.surface,
        title = {
            Text(
                when (mode) {
                    "set" -> "Create a privacy PIN"
                    "remove" -> "Enter PIN to remove it"
                    else -> "Enter your PIN"
                },
                color = tokens.text
            )
        },
        text = {
            Column {
                Text(
                    if (mode == "set") "Hidden videos will only be listed after entering this PIN (4-8 digits)." else "Hidden videos are protected.",
                    style = ChaosType.Caption,
                    color = tokens.textMuted
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { v -> if (v.length <= PinRules.MAX_LENGTH && v.all { it.isDigit() }) pin = v },
                    singleLine = true,
                    label = { Text("PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(pin) { err -> if (err == null) onClose() else { error = err; pin = "" } } }) {
                Text("OK", color = tokens.accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun HiddenVideosSheet(libraryViewModel: LibraryViewModel, onDismiss: () -> Unit) {
    val tokens = chaosTokens()
    val state by libraryViewModel.state.collectAsState()
    val hidden = state.videos.filter { it.id in state.hiddenIds }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = tokens.surface) {
        Column(Modifier.navigationBarsPadding().padding(bottom = ChaosSpacing.L.dp)) {
            Text("Hidden videos", style = ChaosType.Heading, color = tokens.text, modifier = Modifier.padding(horizontal = ChaosSpacing.L.dp))
            if (hidden.isEmpty()) {
                Text(
                    "Nothing hidden. Long-press a video in the library and choose Hide.",
                    style = ChaosType.Body,
                    color = tokens.textMuted,
                    modifier = Modifier.padding(ChaosSpacing.L.dp)
                )
            }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                hidden.forEach { v ->
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = ChaosSpacing.L.dp, vertical = ChaosSpacing.XS.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(v.title, color = tokens.text, style = ChaosType.Body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { libraryViewModel.unhide(v.id) }) { Text("Unhide", color = tokens.accent) }
                    }
                }
            }
            if (hidden.isNotEmpty()) {
                TextButton(onClick = { libraryViewModel.unhideAll() }, modifier = Modifier.padding(horizontal = ChaosSpacing.S.dp)) {
                    Text("Unhide all", color = tokens.accent)
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PickerSheet(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    val tokens = chaosTokens()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = tokens.surface) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(bottom = ChaosSpacing.L.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(title, style = ChaosType.Heading, color = tokens.text, modifier = Modifier.padding(horizontal = ChaosSpacing.L.dp))
            Spacer(Modifier.height(ChaosSpacing.S.dp))
            options.forEachIndexed { index, label ->
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(index) }
                        .padding(horizontal = ChaosSpacing.L.dp, vertical = ChaosSpacing.XS.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = index == selected, onClick = { onSelect(index) })
                    Text(label, style = ChaosType.Body, color = tokens.text)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    val tokens = chaosTokens()
    Spacer(Modifier.height(ChaosSpacing.L.dp))
    Text(text, style = ChaosType.Caption, color = tokens.accent, modifier = Modifier.padding(start = ChaosSpacing.L.dp, bottom = ChaosSpacing.XS.dp))
    HorizontalDivider(color = tokens.surface2)
}

@Composable
private fun Row(title: String, value: String, hint: String? = null, chevron: Boolean = true, onClick: () -> Unit) {
    val tokens = chaosTokens()
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ChaosSpacing.L.dp, vertical = ChaosSpacing.M.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = ChaosType.Body, color = tokens.text)
            if (hint != null) Text(hint, style = ChaosType.Caption, color = tokens.textMuted)
        }
        Text(value, style = ChaosType.Body, color = tokens.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
        if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = tokens.textMuted)
    }
}

@Composable
private fun Toggle(title: String, checked: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    val tokens = chaosTokens()
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = ChaosSpacing.L.dp, vertical = ChaosSpacing.S.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = ChaosType.Body, color = tokens.text)
            if (hint != null) Text(hint, style = ChaosType.Caption, color = tokens.textMuted)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
