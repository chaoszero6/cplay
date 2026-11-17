package com.chaoswares.chaosplay.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chaoswares.chaosplay.ui.theme.ChaosSpacing
import com.chaoswares.chaosplay.ui.theme.ChaosType
import com.chaoswares.chaosplay.ui.theme.ThemeMode
import com.chaoswares.chaosplay.ui.theme.chaosTokens

private data class Slide(val icon: ImageVector, val title: String, val body: String)

private val SLIDES = listOf(
    Slide(
        Icons.Filled.PlayCircle,
        "Plays almost anything",
        "MKV, MP4, AVI and more, with AC3 and DTS sound, picture-in-picture and background play."
    ),
    Slide(
        Icons.Filled.Cloud,
        "Your server, your network",
        "Stream from Jellyfin, Nextcloud, SMB and NFS shares, FTP, SFTP and DLNA, with resume and subtitles."
    ),
    Slide(
        Icons.Filled.TouchApp,
        "Made for your hands",
        "Swipe for volume, brightness and seeking, double-tap to skip, pinch to zoom, and a kids lock."
    )
)

private val SUB_LANGS = listOf(
    "en" to "English", "ta" to "தமிழ்", "hi" to "हिन्दी", "te" to "తెలుగు", "ml" to "മലയാളം",
    "kn" to "ಕನ್ನಡ", "bn" to "বাংলা", "mr" to "मराठी", "es" to "Español"
)

/**
 * First launch, spec section 5 Flow E: "Welcome screen (3 slides at most,
 * skippable) -> permission request with reason -> quick setup: preferred
 * subtitle language, theme -> Library".
 */
@Composable
fun OnboardingScreen(
    initialTheme: ThemeMode,
    onThemePreview: (ThemeMode) -> Unit,
    onDone: (subtitleLanguage: String, theme: ThemeMode) -> Unit
) {
    val tokens = chaosTokens()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var language by rememberSaveable { mutableStateOf("en") }
    var theme by rememberSaveable { mutableStateOf(initialTheme) }
    val permissionStep = SLIDES.size
    val setupStep = SLIDES.size + 1

    val permissions = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    // Whatever the answer, continue: the library explains and re-asks if needed
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { step = setupStep }

    Column(
        Modifier
            .fillMaxSize()
            .background(tokens.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(ChaosSpacing.XL.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (step < permissionStep) TextButton(onClick = { step = permissionStep }) { Text("Skip", color = tokens.textMuted) }
            else Spacer(Modifier.height(48.dp))
        }
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(tween(260)) { w -> dir * w / 3 } + fadeIn(tween(260)))
                    .togetherWith(slideOutHorizontally(tween(220)) { w -> -dir * w / 3 } + fadeOut(tween(180)))
            },
            modifier = Modifier.weight(1f),
            label = "onboarding"
        ) { s ->
            when {
                s < SLIDES.size -> SlidePage(SLIDES[s].icon, SLIDES[s].title, SLIDES[s].body)
                s == permissionStep -> SlidePage(
                    Icons.Filled.VideoLibrary,
                    "Show your videos",
                    "CPlay needs access to the videos on this phone to list them. Nothing is uploaded; " +
                        "notifications are only used for the playback controls."
                )
                else -> SetupPage(
                    language = language,
                    onLanguage = { language = it },
                    theme = theme,
                    onTheme = {
                        theme = it
                        onThemePreview(it)
                    }
                )
            }
        }
        // Page dots
        Row(Modifier.fillMaxWidth().padding(vertical = ChaosSpacing.M.dp), horizontalArrangement = Arrangement.Center) {
            repeat(setupStep + 1) { i ->
                val w by animateDpAsState(if (i == step) 22.dp else 8.dp, tween(200), label = "dot")
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .height(8.dp)
                        .width(w)
                        .clip(CircleShape)
                        .background(if (i == step) tokens.accent else tokens.surface2)
                )
            }
        }
        Button(
            onClick = {
                when {
                    step < SLIDES.size - 1 -> step++
                    step == SLIDES.size - 1 -> step = permissionStep
                    step == permissionStep -> ask.launch(permissions)
                    else -> onDone(language, theme)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text(
                when (step) {
                    permissionStep -> "Allow access"
                    setupStep -> "Start watching"
                    else -> "Next"
                }
            )
        }
    }
}

@Composable
private fun SlidePage(icon: ImageVector, title: String, body: String) {
    val tokens = chaosTokens()
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(140.dp)
                .clip(CircleShape)
                .background(tokens.accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tokens.accent, modifier = Modifier.size(72.dp))
        }
        Spacer(Modifier.height(ChaosSpacing.XL.dp))
        Text(title, style = ChaosType.Title, color = tokens.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(ChaosSpacing.S.dp))
        Text(body, style = ChaosType.Body, color = tokens.textMuted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SetupPage(language: String, onLanguage: (String) -> Unit, theme: ThemeMode, onTheme: (ThemeMode) -> Unit) {
    val tokens = chaosTokens()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Icon(Icons.Filled.Subtitles, contentDescription = null, tint = tokens.accent, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(ChaosSpacing.M.dp))
        Text("Quick setup", style = ChaosType.Title, color = tokens.text)
        Spacer(Modifier.height(ChaosSpacing.L.dp))
        Text("Subtitles in", style = ChaosType.Body, color = tokens.textMuted)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ChaosSpacing.S.dp)) {
            SUB_LANGS.forEach { (code, name) ->
                FilterChip(selected = language == code, onClick = { onLanguage(code) }, label = { Text(name) })
            }
        }
        Spacer(Modifier.height(ChaosSpacing.L.dp))
        Text("Theme", style = ChaosType.Body, color = tokens.textMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(ChaosSpacing.S.dp)) {
            ThemeMode.entries.forEach { t ->
                FilterChip(selected = theme == t, onClick = { onTheme(t) }, label = { Text(t.label) })
            }
        }
        Spacer(Modifier.height(ChaosSpacing.M.dp))
        Text("You can change these any time in Settings.", style = ChaosType.Caption, color = tokens.textMuted)
    }
}
