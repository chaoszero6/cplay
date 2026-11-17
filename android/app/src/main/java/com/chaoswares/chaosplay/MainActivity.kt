package com.chaoswares.chaosplay

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import com.chaoswares.chaosplay.player.PlayQueue
import com.chaoswares.chaosplay.player.QueueItem
import com.chaoswares.chaosplay.ui.MainScaffold
import com.chaoswares.chaosplay.ui.MainTab
import com.chaoswares.chaosplay.ui.library.LibraryScreen
import com.chaoswares.chaosplay.ui.library.LibraryViewModel
import com.chaoswares.chaosplay.ui.network.NetworkScreen
import com.chaoswares.chaosplay.ui.network.NetworkViewModel
import com.chaoswares.chaosplay.ui.player.PipBridge
import com.chaoswares.chaosplay.ui.player.PlayerSessionHost
import com.chaoswares.chaosplay.ui.playlists.PlaylistsScreen
import com.chaoswares.chaosplay.ui.playlists.PlaylistsViewModel
import com.chaoswares.chaosplay.ui.settings.AppSettingsViewModel
import com.chaoswares.chaosplay.ui.settings.SettingsScreen
import com.chaoswares.chaosplay.ui.theme.ChaosMotion
import com.chaoswares.chaosplay.ui.theme.ChaosPlayTheme
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A playback request as saved state. Every request carries a unique nonce:
 * re-opening the same video while the previous player is still fading out
 * must never re-use that (already released) player content.
 */
object PlaybackRequest {
    private const val SEP = '\u0002'
    private var counter = 0L

    fun wrap(queue: PlayQueue): String = "${System.nanoTime()}-${++counter}$SEP${PlayQueue.encode(queue)}"

    fun unwrap(raw: String): PlayQueue? = PlayQueue.decode(raw.substringAfter(SEP, ""))
}

class MainActivity : ComponentActivity() {

    private val libraryViewModel: LibraryViewModel by viewModels()
    private val settingsViewModel: AppSettingsViewModel by viewModels()
    private val playlistsViewModel: PlaylistsViewModel by viewModels()
    private val networkViewModel: NetworkViewModel by viewModels()

    /** Queue requested from outside the composition ("Open with", notification tap). */
    private val externalRequest = MutableStateFlow<PlayQueue?>(null)
    private val inPip = MutableStateFlow(false)

    private val pipListener = Consumer<PictureInPictureModeChangedInfo> { info ->
        inPip.value = info.isInPictureInPictureMode
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        addOnPictureInPictureModeChangedListener(pipListener)
        if (savedInstanceState == null) handleViewIntent(intent)

        setContent {
            val settings by settingsViewModel.state.collectAsState()
            val pip by inPip.collectAsState()
            val pending by externalRequest.collectAsState()

            ChaosPlayTheme(mode = settings.theme) {
                var tab by rememberSaveable { mutableStateOf(MainTab.LIBRARY) }
                // The queue survives rotation and process death as a string
                var queueRaw by rememberSaveable { mutableStateOf<String?>(null) }

                pending?.let {
                    queueRaw = PlaybackRequest.wrap(it)
                    externalRequest.value = null
                }
                val playing: (PlayQueue) -> Unit = { q -> if (!q.isEmpty) queueRaw = PlaybackRequest.wrap(q) }

                if (!settings.loaded) {
                    // One frame while settings load, so the welcome never flashes for returning users
                    androidx.compose.foundation.layout.Box(
                        androidx.compose.ui.Modifier.fillMaxSize().background(com.chaoswares.chaosplay.ui.theme.chaosTokens().bg)
                    )
                } else if (!settings.onboardingDone && queueRaw == null) {
                    com.chaoswares.chaosplay.ui.onboarding.OnboardingScreen(
                        initialTheme = settings.theme,
                        onThemePreview = settingsViewModel::setTheme,
                        onDone = { lang, theme -> settingsViewModel.completeOnboarding(lang, theme) }
                    )
                } else AnimatedContent(
                    targetState = queueRaw,
                    transitionSpec = {
                        fadeIn(tween(ChaosMotion.CONTROLS_FADE_MS)).togetherWith(fadeOut(tween(ChaosMotion.SHEET_SLIDE_MS)))
                    },
                    // Each playback request is its own content, so a new request
                    // always composes a new PlayerSessionHost (new view model).
                    contentKey = { it },
                    label = "playerLayer"
                ) { raw ->
                    val queue = raw?.let { PlaybackRequest.unwrap(it) }
                    if (queue != null) {
                        PlayerSessionHost(
                            queue = queue,
                            inPip = pip,
                            onExit = {
                                queueRaw = null
                                libraryViewModel.refresh()
                                playlistsViewModel.refreshHistory()
                            }
                        )
                    } else {
                        MainScaffold(
                            selected = tab,
                            onSelect = { tab = it },
                            libraryContent = {
                                LibraryScreen(
                                    viewModel = libraryViewModel,
                                    playlists = playlistsViewModel,
                                    onPlay = playing,
                                    onOpenSettings = { tab = MainTab.SETTINGS }
                                )
                            },
                            networkContent = { NetworkScreen(viewModel = networkViewModel, onPlay = playing) },
                            playlistsContent = {
                                PlaylistsScreen(
                                    viewModel = playlistsViewModel,
                                    videos = libraryViewModel.state.collectAsState().value.videos,
                                    onPlay = playing,
                                    onHistoryCleared = { libraryViewModel.refresh() }
                                )
                            },
                            settingsContent = {
                                SettingsScreen(
                                    viewModel = settingsViewModel,
                                    libraryViewModel = libraryViewModel,
                                    networkViewModel = networkViewModel
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    /** "Open with CPlay" from a file manager, browser or messaging app. */
    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val data: Uri = intent.data ?: return
        val title = runCatching {
            contentResolver.query(data, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: data.lastPathSegment.orEmpty()
        externalRequest.value = PlayQueue.single(QueueItem(data.toString(), title))
    }

    /** Auto PiP on Home for Android 8-11 (12+ uses setAutoEnterEnabled). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S &&
            PipBridge.playerVisible && PipBridge.autoEnter
        ) {
            val (n, d) = PipBridge.aspect
            runCatching {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(n, d)).build())
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }

    override fun onDestroy() {
        removeOnPictureInPictureModeChangedListener(pipListener)
        super.onDestroy()
    }
}
