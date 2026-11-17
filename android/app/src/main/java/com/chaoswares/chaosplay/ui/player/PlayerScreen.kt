package com.chaoswares.chaosplay.ui.player

import android.app.Activity
import android.app.Application
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Rational
import android.view.LayoutInflater
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.chaoswares.chaosplay.R
import com.chaoswares.chaosplay.player.AspectMode
import com.chaoswares.chaosplay.player.AspectRules
import com.chaoswares.chaosplay.player.GestureRules
import com.chaoswares.chaosplay.player.OrientationMode
import com.chaoswares.chaosplay.player.PlayQueue
import com.chaoswares.chaosplay.player.TimeFormat
import com.chaoswares.chaosplay.player.VideoTransform
import com.chaoswares.chaosplay.subtitles.SubtitleMarkup
import com.chaoswares.chaosplay.subtitles.SubtitleStyle
import com.chaoswares.chaosplay.ui.theme.ChaosColors
import com.chaoswares.chaosplay.ui.theme.ChaosShape
import com.chaoswares.chaosplay.ui.theme.ChaosSpacing
import com.chaoswares.chaosplay.ui.theme.ChaosType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Which sheet is open. */
/** Width/height of the seek bar's thumb slot; the dot grows inside it (14 dp → 22 dp). */
private val SEEK_THUMB_SLOT = 24.dp

private enum class PlayerSheet { SUBTITLES, AUDIO, SPEED, ASPECT, SLEEP, QUEUE, BOOKMARKS, TV }

/** Process-wide PiP hints read by the activity's onUserLeaveHint (Android 8-11). */
object PipBridge {
    @Volatile var autoEnter: Boolean = false
    @Volatile var aspect: Pair<Int, Int> = 16 to 9
    @Volatile var playerVisible: Boolean = false
}

/**
 * Hosts one playback session. Each time the player opens it gets its own
 * [ViewModelStore], cleared when the player leaves composition, so the next
 * video always starts with a brand-new [PlayerViewModel] and a brand-new
 * ExoPlayer. This is the fix for "play, back, play again = black screen".
 */
@Composable
fun PlayerSessionHost(queue: PlayQueue, inPip: Boolean, onExit: () -> Unit) {
    val owner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    val app = LocalContext.current.applicationContext as Application
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        val vm: PlayerViewModel = viewModel(
            viewModelStoreOwner = owner,
            factory = ViewModelProvider.AndroidViewModelFactory.getInstance(app)
        )
        PlayerScreen(queue = queue, inPip = inPip, onExit = onExit, viewModel = vm)
    }
}

/**
 * Player screen, spec section 4.2. Layer order, bottom to top:
 * video surface -> subtitles -> gesture feedback -> scrim bars -> sheets -> lock overlay.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    queue: PlayQueue,
    inPip: Boolean,
    onExit: () -> Unit,
    viewModel: PlayerViewModel
) {
    val state by viewModel.state.collectAsState()
    val player by viewModel.player.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
    var overflow by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }

    LaunchedEffect(queue) { viewModel.start(queue) }

    // ---- window: immersive, orientation, brightness, PiP hints ----
    DisposableEffect(activity) {
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        PipBridge.playerVisible = true
        onDispose {
            PipBridge.playerVisible = false
            PipBridge.autoEnter = false
            if (window != null) {
                WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.systemBars())
                window.attributes = window.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { activity?.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build()) }
            }
            // Covers exits that never pass the back button
            viewModel.release()
        }
    }

    LaunchedEffect(state.orientation) {
        activity?.requestedOrientation = when (state.orientation) {
            OrientationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    LaunchedEffect(state.brightnessLevel) {
        val level = state.brightnessLevel ?: return@LaunchedEffect
        activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = level.coerceIn(0.01f, 1f) } }
    }

    LaunchedEffect(state.isPlaying, state.prefs.keepScreenOn) {
        val w = activity?.window ?: return@LaunchedEffect
        if (state.isPlaying && state.prefs.keepScreenOn) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    LaunchedEffect(state.isPlaying, state.prefs.autoPip, state.videoWidth, state.videoHeight) {
        val ratio = AspectRules.pipRatio(state.videoWidth, state.videoHeight)
        PipBridge.aspect = ratio
        PipBridge.autoEnter = state.prefs.autoPip && state.isPlaying
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && activity != null) {
            runCatching {
                activity.setPictureInPictureParams(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(ratio.first, ratio.second))
                        .setAutoEnterEnabled(state.prefs.autoPip && state.isPlaying)
                        .setSeamlessResizeEnabled(true)
                        .build()
                )
            }
        }
    }

    // ---- lifecycle: pause when the app leaves the screen (unless background play) ----
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // ON_STOP means no window shows the video any more: home pressed, the
        // screen turned off, or the PiP window was closed (a visible PiP
        // window only pauses the activity, it does not stop it).
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.onAppBackgrounded(inPip = false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ---- exit ----
    var exiting by remember { mutableStateOf(false) }
    val exitAlpha by animateFloatAsState(
        targetValue = if (exiting) 0f else 1f,
        animationSpec = tween(com.chaoswares.chaosplay.ui.theme.ChaosMotion.CONTROLS_FADE_MS),
        label = "playerExit"
    )
    val requestExit: () -> Unit = {
        if (!exiting) {
            exiting = true
            scope.launch {
                viewModel.exitAndSave()
                onExit()
            }
        }
    }
    BackHandler(enabled = !inPip) {
        if (sheet != null) sheet = null else requestExit()
    }

    LaunchedEffect(sheet, overflow, infoOpen) {
        if (sheet != null || overflow || infoOpen) viewModel.keepControls() else if (state.controlsVisible) viewModel.scheduleControlsHide()
    }

    val pickSubtitle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.addExternalSubtitle(uri)
    }

    val enterPip: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {
            val r = AspectRules.pipRatio(state.videoWidth, state.videoHeight)
            runCatching {
                activity.enterPictureInPictureMode(
                    PictureInPictureParams.Builder().setAspectRatio(Rational(r.first, r.second)).build()
                )
            }.onFailure { viewModel.flash("Picture-in-picture is not available on this device") }
        } else {
            viewModel.flash("Picture-in-picture needs Android 8 or newer")
        }
    }

    val takeScreenshot: () -> Unit = {
        captureFrame(playerView, state.videoWidth, state.videoHeight) { bmp -> viewModel.saveScreenshot(bmp) }
    }

    val density = LocalDensity.current
    val gestures = state.prefs.gestures
    val haptics = com.chaoswares.chaosplay.ui.theme.rememberHaptics(state.prefs.haptics)
    // Kids lock: colourful ripples where small fingers land (spec 7.3)
    val touchEffects = remember { androidx.compose.runtime.mutableStateListOf<TouchEffect>() }
    var showBuffering by remember { mutableStateOf(false) }
    LaunchedEffect(state.isBuffering) {
        // Short stalls never flash a spinner (spec 1: "no spinners where avoidable")
        if (state.isBuffering) {
            delay(com.chaoswares.chaosplay.ui.theme.ChaosMotion.BUFFERING_DELAY_MS)
            showBuffering = true
        } else showBuffering = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = exitAlpha }
            .background(Color.Black)
            .semantics { contentDescription = "Video player" }
            .pointerInput(state.locked, inPip, state.tv != null) {
                if (inPip || state.tv != null) return@pointerInput
                if (state.locked) {
                    // Locked: every touch counts immediately. With double-tap
                    // detection on, two quick corner taps merged into one
                    // "double tap" and the unlock sequence lost a corner.
                    detectTapGestures(onTap = { offset ->
                        when (viewModel.onCornerTouch(offset.x, offset.y, size.width.toFloat(), size.height.toFloat())) {
                            PlayerViewModel.CornerResult.UNLOCKED -> haptics.strong()
                            PlayerViewModel.CornerResult.PROGRESS -> haptics.tick()
                            else -> Unit
                        }
                        if (state.prefs.kidsTouchEffects) {
                            touchEffects += TouchEffect(offset, System.nanoTime())
                            if (touchEffects.size > 12) touchEffects.removeAt(0)
                        }
                    })
                    return@pointerInput
                }
                detectTapGestures(
                    onTap = { viewModel.toggleControls() },
                    onDoubleTap = { offset ->
                        val g = viewModel.onDoubleTap(offset.x, size.width.toFloat())
                        if (g == GestureRules.Gesture.SKIP_BACK || g == GestureRules.Gesture.SKIP_FORWARD) haptics.tick()
                    },
                    onPress = {
                        val holdJob = scope.launch {
                            delay(550)
                            if (viewModel.startHoldSpeed()) haptics.strong()
                        }
                        tryAwaitRelease()
                        holdJob.cancel()
                        viewModel.endHoldSpeed()
                    }
                )
            }
            .pointerInput(state.locked, inPip, gestures, state.tv != null) {
                if (inPip || state.locked || state.tv != null) return@pointerInput
                playerGestures(viewModel, gestures, activity, enterPip)
            }
    ) {
        VideoSurface(
            player = player,
            aspectMode = state.aspectMode,
            transform = state.transform,
            videoWidth = state.videoWidth,
            videoHeight = state.videoHeight,
            onView = { playerView = it }
        )

        SubtitleOverlay(
            primary = state.mainSubtitleText,
            secondary = if (state.subtitlesOff) "" else state.secondaryCueText,
            style = state.subtitleStyle,
            pip = inPip,
            controlsUp = state.controlsVisible && !state.locked && !inPip,
            onMove = { deltaPercent ->
                viewModel.setSubtitleStyle(
                    state.subtitleStyle.copy(bottomMarginPercent = state.subtitleStyle.bottomMarginPercent + deltaPercent)
                )
            },
            onJumpLine = { forward -> viewModel.jumpSubtitleLine(forward) }
        )

        if (!inPip) {
            // ---- gesture feedback: instant in, 600 ms fade out (spec 2.5) ----
            // Swipe-to-seek shows the same frame preview as the seek bar
            com.chaoswares.chaosplay.ui.theme.Fading(state.seekPreviewMs?.let { it to (state.seekLabel ?: "") }) { (pos, label) ->
                Box(Modifier.fillMaxWidth().padding(top = 72.dp), contentAlignment = Alignment.TopCenter) {
                    SeekPreviewCard(positionMs = pos, label = label, previewFrame = viewModel::previewFrame)
                }
            }
            com.chaoswares.chaosplay.ui.theme.Fading(state.speedLabel) { GestureBadge(text = it, icon = Icons.Filled.Speed) }
            com.chaoswares.chaosplay.ui.theme.Fading(state.zoomLabel) { GestureBadge(text = it, icon = Icons.Filled.ZoomOutMap) }
            com.chaoswares.chaosplay.ui.theme.Fading(if (state.brightnessVisible) state.brightnessLevel else null) {
                LevelIndicator(level = it, isVolume = false)
            }
            com.chaoswares.chaosplay.ui.theme.Fading(state.volumeLevel) {
                LevelIndicator(level = (it / 2f).coerceIn(0f, 1f), isVolume = true, boosted = it > 1f, percent = (it * 100).toInt())
            }
            com.chaoswares.chaosplay.ui.theme.Fading(state.skip) { SkipRipple(it) }
            PlayPausePulse(pulse = state.pulse, playing = state.isPlaying)

            com.chaoswares.chaosplay.ui.theme.Fading(
                if (showBuffering && state.isBuffering && !state.locked && state.errorMessage == null) Unit else null,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(150))
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(48.dp))
                }
            }

            // ---- controls: 200 ms fade (spec 2.5) ----
            AnimatedVisibility(
                visible = state.controlsVisible && !state.locked,
                enter = fadeIn(tween(com.chaoswares.chaosplay.ui.theme.ChaosMotion.CONTROLS_FADE_MS)),
                exit = fadeOut(tween(com.chaoswares.chaosplay.ui.theme.ChaosMotion.CONTROLS_FADE_MS))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.systemBars))
                ) {
                    PlayerTopBar(
                        state = state,
                        exiting = exiting,
                        onBack = requestExit,
                        onSubtitles = { sheet = PlayerSheet.SUBTITLES },
                        onSubtitlesLong = { viewModel.cycleSubtitle() },
                        onAudio = { sheet = PlayerSheet.AUDIO },
                        onTv = { sheet = PlayerSheet.TV },
                        overflowOpen = overflow,
                        onOverflowChange = { overflow = it },
                        onAction = { action ->
                            overflow = false
                            when (action) {
                                Overflow.INFO -> infoOpen = true
                                Overflow.AB -> viewModel.pressAbRepeat()
                                Overflow.SLEEP -> sheet = PlayerSheet.SLEEP
                                Overflow.SCREENSHOT -> takeScreenshot()
                                Overflow.SHARE -> viewModel.share(context)
                                Overflow.SPEED -> sheet = PlayerSheet.SPEED
                                Overflow.ASPECT -> sheet = PlayerSheet.ASPECT
                                Overflow.QUEUE -> sheet = PlayerSheet.QUEUE
                                Overflow.BOOKMARKS -> sheet = PlayerSheet.BOOKMARKS
                                Overflow.BACKGROUND -> viewModel.toggleBackgroundPlay()
                            }
                        }
                    )

                    Spacer(Modifier.weight(1f))

                    PlayerCenterControls(
                        isPlaying = state.isPlaying,
                        stepLabel = state.seekStepLabel,
                        onPlayPause = viewModel::playPause,
                        onPlayLong = viewModel::toggleBackgroundPlay,
                        onSkipBack = viewModel::skipBack,
                        onSkipForward = viewModel::skipForward
                    )

                    Spacer(Modifier.weight(1f))

                    PlayerBottomBar(
                        state = state,
                        previewFrame = viewModel::previewFrame,
                        onSeek = viewModel::seekTo,
                        onToggleTime = viewModel::toggleTimeDisplay,
                        onToggleLock = viewModel::toggleLock,
                        onSpeed = { sheet = PlayerSheet.SPEED },
                        onAspect = { sheet = PlayerSheet.ASPECT },
                        onPrevious = viewModel::previous,
                        onNext = viewModel::next,
                        onNextLong = { sheet = PlayerSheet.QUEUE },
                        onDecoder = viewModel::cycleDecoder,
                        onRotate = viewModel::cycleOrientation,
                        onPip = enterPip
                    )
                }
            }

            if (state.locked) {
                TouchEffectsLayer(touchEffects)
            }
            AnimatedVisibility(visible = state.locked, enter = fadeIn(tween(250)), exit = fadeOut(tween(250))) {
                LockOverlay(completedCorners = state.lockProgress)
            }
            LaunchedEffect(state.locked) { if (!state.locked) touchEffects.clear() }

            com.chaoswares.chaosplay.ui.theme.Fading(
                state.message,
                enter = fadeIn(tween(150)) + androidx.compose.animation.slideInVertically(tween(200)) { it / 2 },
                exit = fadeOut(tween(250)) + androidx.compose.animation.slideOutVertically(tween(250)) { it / 2 }
            ) { Toast(it) }

            state.tv?.let { tv ->
                TvRemoteOverlay(
                    tv = tv,
                    title = state.title,
                    onPlayPause = viewModel::tvPlayPause,
                    onSeek = viewModel::tvSeek,
                    onSkip = viewModel::tvSkip,
                    onStop = { viewModel.stopTv() }
                )
            }

            state.errorMessage?.let {
                ErrorOverlay(message = it, onRetry = viewModel::retry, onBack = requestExit, canNext = state.queue.hasNext, onNext = viewModel::next)
            }
        }
    }

    // ---- sheets ----
    if (sheet != null && !inPip) {
        ModalBottomSheet(
            onDismissRequest = { sheet = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = sheet == PlayerSheet.SUBTITLES || sheet == PlayerSheet.QUEUE),
            containerColor = ChaosColors.Surface,
            contentColor = ChaosColors.Text
        ) {
            when (sheet) {
                PlayerSheet.SUBTITLES -> SubtitleSheet(
                    state = state,
                    onSelectTrack = viewModel::selectSubtitle,
                    onSelectExternal = { viewModel.selectExternal(it) },
                    onSelectSecondary = viewModel::selectSecondary,
                    onAddFile = { pickSubtitle.launch(arrayOf("*/*")) },
                    onDelay = viewModel::nudgeSubtitleDelay,
                    onResetDelay = { viewModel.setSubtitleDelay(0) },
                    onSpeed = viewModel::setSubtitleSpeed,
                    onEncoding = viewModel::setSubtitleEncoding,
                    onStyle = viewModel::setSubtitleStyle,
                    onDismiss = { sheet = null }
                )
                PlayerSheet.AUDIO -> AudioSheet(
                    state = state,
                    onSelect = viewModel::selectAudio,
                    onBoost = viewModel::setBoost,
                    onPreset = viewModel::setEqPreset,
                    onDelay = viewModel::nudgeAudioDelay,
                    onResetDelay = { viewModel.setAudioDelay(0) },
                    onLevelling = viewModel::setLevelling,
                    onDismiss = { sheet = null }
                )
                PlayerSheet.SPEED -> SpeedSheet(state.speed, viewModel::setSpeed) { sheet = null }
                PlayerSheet.ASPECT -> AspectSheet(
                    current = state.aspectMode,
                    transform = state.transform,
                    onSelect = viewModel::setAspect,
                    onZoom = viewModel::setZoom,
                    onMirror = viewModel::toggleMirror,
                    onFlip = viewModel::toggleFlip,
                    onRotate = viewModel::rotateVideo,
                    onReset = viewModel::resetTransform,
                    onDismiss = { sheet = null }
                )
                PlayerSheet.SLEEP -> SleepSheet(
                    remainingMs = state.sleepRemainingMs,
                    onStart = viewModel::startSleepTimer,
                    onCancel = viewModel::cancelSleepTimer,
                    onDismiss = { sheet = null }
                )
                PlayerSheet.QUEUE -> QueueSheet(
                    queue = state.queue,
                    onPlay = viewModel::playQueueIndex,
                    onRemove = viewModel::removeFromQueue,
                    onMove = viewModel::moveInQueue,
                    onShuffle = viewModel::toggleShuffle,
                    onRepeat = viewModel::cycleRepeat,
                    onDismiss = { sheet = null }
                )
                PlayerSheet.TV -> TvSheet(
                    devices = state.tvDevices,
                    scanning = state.tvScanning,
                    castAvailable = state.castAvailable,
                    onScan = viewModel::startTvScan,
                    onStopScan = viewModel::stopTvScan,
                    onPick = { device ->
                        sheet = null
                        viewModel.playOnTv(device)
                    },
                    onDismiss = { sheet = null }
                )
                PlayerSheet.BOOKMARKS -> BookmarksSheet(
                    bookmarks = state.bookmarks,
                    positionMs = state.positionMs,
                    onAdd = viewModel::addBookmark,
                    onJump = { viewModel.seekTo(it.positionMs); sheet = null },
                    onRemove = viewModel::removeBookmark,
                    onRename = viewModel::renameBookmark,
                    onDismiss = { sheet = null }
                )
                null -> Unit
            }
        }
    }

    if (infoOpen && !inPip) {
        PlaybackInfoDialog(state = state, onDismiss = { infoOpen = false })
    }
}

/**
 * Drag, pinch and two-finger gestures (spec 4.2), with one decision per
 * gesture: once a drag is classified as seek it stays seek, so a slightly
 * diagonal swipe never jumps between seek and volume mid-way.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.playerGestures(
    vm: PlayerViewModel,
    gestures: GestureRules.GestureSettings,
    activity: Activity?,
    enterPip: () -> Unit
) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        val startX = down.position.x
        var mode = 0 // 0 none, 1 seek, 2 bright, 3 vol, 4 pip, 5 two-pending, 6 zoom, 7 speed, 9 ignored
        var totalDx = 0f
        var totalDy = 0f
        var startSpan = 0f
        var lastSpan = 0f
        var centroidStartY = 0f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (pressed.size >= 2) {
                val p1 = pressed[0].position
                val p2 = pressed[1].position
                val span = (p1 - p2).getDistance()
                val cy = (p1.y + p2.y) / 2f
                if (mode < 5) {
                    if (mode == 1) vm.commitDragSeek()
                    mode = 5
                    startSpan = span
                    lastSpan = span
                    centroidStartY = cy
                    vm.gestureActive = true
                    vm.endHoldSpeed()
                    vm.beginTwoFingerSpeed()
                }
                if (mode == 5) {
                    when (GestureRules.classifyTwoFinger(startSpan, span, cy - centroidStartY, h, gestures)) {
                        GestureRules.Gesture.ZOOM -> mode = 6
                        GestureRules.Gesture.SPEED -> mode = 7
                        else -> Unit
                    }
                }
                if (mode == 6 && lastSpan > 0f) vm.onPinch(span / lastSpan)
                if (mode == 7) vm.onTwoFingerSpeed(cy - centroidStartY, h)
                lastSpan = span
                event.changes.forEach { it.consume() }
                continue
            }
            if (mode >= 5) {
                event.changes.forEach { it.consume() }
                continue
            }
            val change = pressed.first()
            val delta = change.positionChange()
            totalDx += delta.x
            totalDy += delta.y
            if (mode == 0 && (abs(totalDx) > slop || abs(totalDy) > slop)) {
                mode = when {
                    GestureRules.inEdgeZone(startX, w) -> 9
                    abs(totalDx) > abs(totalDy) -> if (gestures.seekEnabled) 1 else 9
                    GestureRules.inPipZone(startX, w, gestures) -> 4
                    GestureRules.halfOf(startX, w) == GestureRules.ScreenHalf.LEFT -> if (gestures.brightnessEnabled) 2 else 9
                    else -> if (gestures.volumeEnabled) 3 else 9
                }
                vm.gestureActive = mode != 9
                vm.endHoldSpeed()
                if (mode == 2 || mode == 3) vm.beginLevelDrag(isVolume = mode == 3, currentBrightness = currentBrightness(activity))
            }
            when (mode) {
                1 -> vm.onSeekDrag(delta.x, w)
                2 -> vm.onBrightnessDrag(delta.y, h)
                3 -> vm.onVolumeDrag(delta.y, h)
            }
            if (mode in 1..4) change.consume()
        }
        when (mode) {
            1 -> vm.commitDragSeek()
            4 -> if (GestureRules.isPipSwipe(startX, totalDx, totalDy, w, h, gestures)) enterPip()
        }
        if (mode != 0) vm.clearGestureFeedback()
        vm.gestureActive = false
    }
}

private fun currentBrightness(activity: Activity?): Float {
    val own = activity?.window?.attributes?.screenBrightness ?: -1f
    if (own >= 0f) return own
    val system = runCatching {
        Settings.System.getInt(activity?.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    }.getOrDefault(128)
    return (system / 255f).coerceIn(0.05f, 1f)
}

/** Grabs the current video frame: TextureView directly, SurfaceView via PixelCopy. */
private fun captureFrame(view: PlayerView?, videoW: Int, videoH: Int, onResult: (Bitmap?) -> Unit) {
    val surface = view?.videoSurfaceView
    when {
        surface is TextureView -> onResult(surface.bitmap)
        surface is SurfaceView && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> {
            val w = if (videoW > 0) videoW.coerceAtMost(3840) else surface.width
            val h = if (videoH > 0) videoH.coerceAtMost(2160) else surface.height
            if (w <= 0 || h <= 0) {
                onResult(null)
                return
            }
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            runCatching {
                PixelCopy.request(surface, bitmap, { result ->
                    onResult(if (result == PixelCopy.SUCCESS) bitmap else null)
                }, Handler(Looper.getMainLooper()))
            }.onFailure { onResult(null) }
        }
        else -> onResult(null)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(
    player: ExoPlayer?,
    aspectMode: AspectMode,
    transform: VideoTransform,
    videoWidth: Int,
    videoHeight: Int,
    onView: (PlayerView?) -> Unit
) {
    val layout = AspectRules.layoutFor(aspectMode)
    val useTexture = AspectRules.needsTextureView(transform)
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val rotateScale = AspectRules.rotationFitScale(transform.rotationDegrees, wPx, hPx)
        val boxModifier = when {
            layout.forcedAspect != null -> Modifier.aspectRatio(layout.forcedAspect, matchHeightConstraintsFirst = wPx / hPx > layout.forcedAspect)
            layout.nativeSize && videoWidth > 0 && videoHeight > 0 -> with(density) {
                Modifier.size(videoWidth.toDp(), videoHeight.toDp())
            }
            else -> Modifier.fillMaxSize()
        }
        key(useTexture) {
            AndroidView(
                factory = { ctx ->
                    val res = if (useTexture) R.layout.player_view_texture else R.layout.player_view_surface
                    (LayoutInflater.from(ctx).inflate(res, null, false) as PlayerView).apply {
                        useController = false
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        setKeepContentOnPlayerReset(true)
                        // Subtitles are drawn by SubtitleOverlay so style/delay apply to every track
                        subtitleView?.visibility = View.GONE
                    }.also(onView)
                },
                update = { view ->
                    if (view.player !== player) view.player = player
                    view.resizeMode = when (layout.resize) {
                        AspectRules.Resize.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        AspectRules.Resize.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        AspectRules.Resize.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    }
                },
                onRelease = { view ->
                    view.player = null
                    onView(null)
                },
                modifier = boxModifier.graphicsLayer {
                    val s = transform.zoom * rotateScale
                    scaleX = s * if (transform.mirrored) -1f else 1f
                    scaleY = s * if (transform.flipped) -1f else 1f
                    rotationZ = transform.rotationDegrees.toFloat()
                    translationX = transform.panX
                    translationY = transform.panY
                }
            )
        }
    }
}

/**
 * Subtitle text drawn by the app (spec 4 "Style", "Clear text: border and
 * shadow"). Drag up/down moves it; swipe sideways jumps to the
 * previous/next line (spec 4.2 "Subtitle drag/scroll").
 */
@Composable
private fun SubtitleOverlay(
    primary: String,
    secondary: String,
    style: SubtitleStyle,
    pip: Boolean,
    controlsUp: Boolean,
    onMove: (Int) -> Unit,
    onJumpLine: (Boolean) -> Unit
) {
    if (primary.isBlank() && secondary.isBlank()) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val height = maxHeight
        val bottom = if (pip) 4.dp else height * (style.bottomMarginPercent / 100f) + if (controlsUp) 96.dp else 0.dp
        val textSize = if (pip) (style.sizeSp * 0.6f).sp else style.sizeSp.sp
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottom, start = 24.dp, end = 24.dp)
                .semantics { contentDescription = "Subtitle: " + SubtitleMarkup.plain(primary) }
                .pointerInput(Unit) {
                    var dx = 0f
                    var dy = 0f
                    detectDragGestures(
                        onDragStart = { dx = 0f; dy = 0f },
                        onDragEnd = {
                            if (abs(dx) > abs(dy) && abs(dx) > 80f) onJumpLine(dx < 0)
                        }
                    ) { change, amount ->
                        change.consume()
                        dx += amount.x
                        dy += amount.y
                        if (abs(dy) > abs(dx) && abs(amount.y) > 0f) {
                            val percent = (-amount.y / size.height * 100f)
                            if (abs(percent) >= 0.5f) onMove(percent.toInt().let { if (it == 0) (if (percent > 0) 1 else -1) else it })
                        }
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (secondary.isNotBlank()) SubtitleText(secondary, style, textSize.value * 0.85f)
            if (primary.isNotBlank()) SubtitleText(primary, style, textSize.value)
        }
    }
}

@Composable
private fun SubtitleText(markup: String, style: SubtitleStyle, sizeSp: Float) {
    val text: AnnotatedString = remember(markup) {
        buildAnnotatedString {
            SubtitleMarkup.spans(markup).forEach { span ->
                withStyle(
                    SpanStyle(
                        fontStyle = if (span.italic) FontStyle.Italic else null,
                        fontWeight = if (span.bold) FontWeight.Bold else null,
                        textDecoration = if (span.underline) TextDecoration.Underline else null
                    )
                ) { append(span.text) }
            }
        }
    }
    Text(
        text = text,
        color = Color(style.colorArgb),
        textAlign = TextAlign.Center,
        style = TextStyle(
            fontSize = sizeSp.sp,
            lineHeight = (sizeSp * 1.25f).sp,
            fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Medium,
            shadow = if (style.outline) Shadow(Color.Black, Offset(2f, 2f), 6f) else null
        ),
        modifier = Modifier
            .padding(vertical = 1.dp)
            .then(
                if (style.background) Modifier.background(Color(0xB3000000), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp)
                else Modifier
            )
    )
}

private enum class Overflow { INFO, QUEUE, BOOKMARKS, AB, SLEEP, BACKGROUND, SCREENSHOT, SHARE, SPEED, ASPECT }

/**
 * Top bar, spec 4.2: "back, title, [CC] [♫] [⋮]"; the overflow holds info,
 * A-B repeat, sleep timer, screenshot and share, plus queue, bookmarks
 * and background play.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PlayerTopBar(
    state: PlayerUiState,
    exiting: Boolean,
    onBack: () -> Unit,
    onSubtitles: () -> Unit,
    onSubtitlesLong: () -> Unit,
    onAudio: () -> Unit,
    onTv: () -> Unit,
    overflowOpen: Boolean,
    onOverflowChange: (Boolean) -> Unit,
    onAction: (Overflow) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xCC000000), Color(0x00000000))))
            .padding(start = ChaosSpacing.S.dp, end = ChaosSpacing.S.dp, top = ChaosSpacing.XS.dp, bottom = ChaosSpacing.L.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, enabled = !exiting) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = ChaosSpacing.S.dp)
        ) {
            Text(
                text = state.title.ifBlank { "Playing" },
                style = ChaosType.Body,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val sub = listOfNotNull(
                state.sourceLabel.takeIf { it != "This device" },
                if (state.queue.size > 1) "${state.queue.index + 1} of ${state.queue.size}" else null,
                state.sleepRemainingMs?.let { state.sleepLabel },
                if (state.prefs.backgroundPlay) "Background play" else null
            ).joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(sub, style = ChaosType.Caption, color = Color(0xCCFFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .combinedClickable(onClick = onSubtitles, onLongClick = onSubtitlesLong, onClickLabel = "Subtitles"),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Subtitles, contentDescription = "Subtitles", tint = if (state.mainSubtitleText.isNotEmpty() || state.primaryExternalKey != null) ChaosColors.Accent else Color.White)
        }
        IconButton(onClick = onTv) {
            Icon(Icons.Filled.Cast, contentDescription = "Play on TV", tint = Color.White)
        }
        IconButton(onClick = onAudio) {
            Icon(Icons.Filled.MusicNote, contentDescription = "Audio", tint = Color.White)
        }
        Box {
            IconButton(onClick = { onOverflowChange(true) }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = Color.White)
            }
            DropdownMenu(expanded = overflowOpen, onDismissRequest = { onOverflowChange(false) }) {
                OverflowItem("Info", Icons.Filled.Info) { onAction(Overflow.INFO) }
                OverflowItem("Queue (${state.queue.size})", Icons.AutoMirrored.Filled.QueueMusic) { onAction(Overflow.QUEUE) }
                OverflowItem("Bookmarks (${state.bookmarks.size})", Icons.Filled.Bookmark) { onAction(Overflow.BOOKMARKS) }
                OverflowItem(
                    "A-B repeat ${state.abRepeat.label()}",
                    Icons.Filled.Repeat,
                    tint = if (state.abRepeat.isActive || state.abRepeat.isArmed) ChaosColors.Accent2 else null
                ) { onAction(Overflow.AB) }
                OverflowItem(state.sleepLabel, Icons.Filled.Bedtime, tint = if (state.sleepRemainingMs != null) ChaosColors.Accent2 else null) {
                    onAction(Overflow.SLEEP)
                }
                OverflowItem(
                    if (state.prefs.backgroundPlay) "Background play: on" else "Background play: off",
                    Icons.Filled.Headphones,
                    tint = if (state.prefs.backgroundPlay) ChaosColors.Accent2 else null
                ) { onAction(Overflow.BACKGROUND) }
                OverflowItem("Screenshot", Icons.Filled.CameraAlt) { onAction(Overflow.SCREENSHOT) }
                OverflowItem("Share", Icons.Filled.Share) { onAction(Overflow.SHARE) }
                OverflowItem("Speed", Icons.Filled.Speed) { onAction(Overflow.SPEED) }
                OverflowItem("Aspect & zoom", Icons.Filled.ZoomOutMap) { onAction(Overflow.ASPECT) }
            }
        }
    }
}

@Composable
private fun OverflowItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color? = null,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = tint ?: LocalContentColor.current) },
        onClick = onClick
    )
}

/** Spec 4.2 wireframe centre: "⏪10  ▶/⏸  10⏩". Long press play toggles background play (7.2). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PlayerCenterControls(
    isPlaying: Boolean,
    stepLabel: String,
    onPlayPause: () -> Unit,
    onPlayLong: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onSkipBack, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Filled.Replay10, contentDescription = "Back $stepLabel", tint = Color.White, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.width(ChaosSpacing.XL.dp))
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(Color(0x66000000), CircleShape)
                .combinedClickable(onClick = onPlayPause, onLongClick = onPlayLong),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.animation.AnimatedContent(
                targetState = isPlaying,
                transitionSpec = {
                    (fadeIn(tween(160)) + androidx.compose.animation.scaleIn(tween(200), initialScale = 0.6f))
                        .togetherWith(fadeOut(tween(120)) + androidx.compose.animation.scaleOut(tween(150), targetScale = 0.6f))
                },
                label = "playPause"
            ) { playing ->
                Icon(
                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(44.dp)
                )
            }
        }
        Spacer(Modifier.width(ChaosSpacing.XL.dp))
        IconButton(onClick = onSkipForward, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Filled.Forward10, contentDescription = "Forward $stepLabel", tint = Color.White, modifier = Modifier.size(40.dp))
        }
    }
}

/**
 * Seek bar and bottom buttons, spec 4.2:
 * "00:12:34 ━━━●━━━━  -01:05:20" and
 * "[🔒] [Speed] [Aspect] [⏮] [⏭] [Decoder] [Rotate] [PiP]".
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PlayerBottomBar(
    state: PlayerUiState,
    previewFrame: suspend (Long) -> Bitmap?,
    onSeek: (Long) -> Unit,
    onToggleTime: () -> Unit,
    onToggleLock: () -> Unit,
    onSpeed: () -> Unit,
    onAspect: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onNextLong: () -> Unit,
    onDecoder: () -> Unit,
    onRotate: () -> Unit,
    onPip: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0x00000000), Color(0xD9000000))))
            .padding(start = ChaosSpacing.M.dp, end = ChaosSpacing.M.dp, top = ChaosSpacing.XL.dp, bottom = ChaosSpacing.S.dp)
    ) {
        var dragPosition by remember { mutableStateOf<Long?>(null) }
        val displayPosition = dragPosition ?: state.positionMs
        val elapsed = TimeFormat.formatClock(displayPosition)
        val right = if (state.showRemaining) {
            TimeFormat.formatClockRemaining(state.durationMs, displayPosition)
        } else {
            TimeFormat.formatClock(state.durationMs)
        }

        // Thumbnail preview above the bar while dragging (spec 4.2)
        val drag = dragPosition
        AnimatedVisibility(visible = drag != null, enter = fadeIn(tween(120)), exit = fadeOut(tween(200))) {
            var last by remember { mutableStateOf(0L) }
            if (drag != null) last = drag
            Box(Modifier.fillMaxWidth().padding(bottom = ChaosSpacing.S.dp), contentAlignment = Alignment.Center) {
                SeekPreviewCard(positionMs = last, label = TimeFormat.formatClock(last), previewFrame = previewFrame)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = elapsed, style = ChaosType.PlayerTime, color = Color.White)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = ChaosSpacing.S.dp)
            ) {
                val seekInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val dragging by seekInteraction.collectIsDraggedAsState()
                val pressed by seekInteraction.collectIsPressedAsState()
                val thumbSize by androidx.compose.animation.core.animateDpAsState(
                    if (dragging || pressed) 22.dp else 14.dp, tween(120), label = "thumb"
                )
                Slider(
                    interactionSource = seekInteraction,
                    thumb = {
                        // Fixed-size slot, dot drawn centred inside it: the slider then
                        // lines the dot up with the middle of the track at every size
                        Box(Modifier.size(SEEK_THUMB_SLOT), contentAlignment = Alignment.Center) {
                            Box(
                                Modifier
                                    .size(thumbSize)
                                    .shadow(2.dp, CircleShape)
                                    .background(ChaosColors.Accent, CircleShape)
                            )
                        }
                    },
                    value = if (state.durationMs > 0) (displayPosition.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f,
                    onValueChange = { fraction -> dragPosition = (fraction * state.durationMs).toLong() },
                    onValueChangeFinished = {
                        dragPosition?.let(onSeek)
                        dragPosition = null
                    },
                    enabled = state.durationMs > 0,
                    colors = SliderDefaults.colors(
                        thumbColor = ChaosColors.Accent,
                        activeTrackColor = ChaosColors.Accent,
                        inactiveTrackColor = Color(0x66FFFFFF)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Seek bar" }
                )
                // A-B markers and bookmark ticks on the bar (spec 4.2)
                if (state.durationMs > 0) {
                    BoxWithConstraints(Modifier.fillMaxWidth().align(Alignment.Center)) {
                        // The track runs from half a thumb slot in from each end
                        val barWidth = maxWidth - SEEK_THUMB_SLOT
                        fun x(ms: Long) = SEEK_THUMB_SLOT / 2 + barWidth * (ms.toFloat() / state.durationMs).coerceIn(0f, 1f)
                        state.abRepeat.aMs?.let { Marker(x(it), ChaosColors.Accent2) }
                        state.abRepeat.bMs?.let { Marker(x(it), ChaosColors.Accent2) }
                        state.bookmarks.forEach { Marker(x(it.positionMs), Color.White, small = true) }
                    }
                }
            }
            Text(
                text = right,
                style = ChaosType.PlayerTime,
                color = Color.White,
                modifier = Modifier
                    .clickable(onClickLabel = "Toggle remaining time", onClick = onToggleTime)
                    .padding(4.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BarButton(Icons.Filled.Lock, "Lock", null, onToggleLock)
            BarButton(Icons.Filled.Speed, "Speed", TimeFormat.formatSpeed(state.speed), onSpeed)
            BarButton(Icons.Filled.ZoomOutMap, "Aspect", state.aspectMode.label, onAspect)
            BarButton(Icons.Filled.SkipPrevious, "Previous", null, onPrevious)
            BarButton(Icons.Filled.SkipNext, "Next", null, onNext, onLongClick = onNextLong, enabled = state.queue.hasNext)
            BarButton(Icons.Filled.Memory, "Decoder", state.decoderMode.shortLabel, onDecoder)
            BarButton(Icons.Filled.ScreenRotation, "Rotate screen", state.orientation.label, onRotate)
            BarButton(Icons.Filled.PictureInPictureAlt, "Picture in picture", null, onPip)
        }
    }
}

/**
 * Frame preview with the target time (spec 4.2). The frame is fetched per
 * second of movement and the last good frame stays up while the next one
 * decodes, so dragging never flickers; sources without frames show time only.
 */
@Composable
private fun SeekPreviewCard(positionMs: Long, label: String, previewFrame: suspend (Long) -> Bitmap?) {
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(positionMs / 1000) {
        previewFrame(positionMs)?.let { frame = it }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .background(Color(0xCC000000), RoundedCornerShape(10.dp))
            .padding(6.dp)
    ) {
        frame?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Preview",
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .width(192.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(6.dp))
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(label, color = Color.White, style = ChaosType.Heading, modifier = Modifier.padding(horizontal = 8.dp))
    }
}

@Composable
private fun Marker(x: androidx.compose.ui.unit.Dp, color: Color, small: Boolean = false) {
    Box(
        Modifier
            .offset(x = x - 1.dp)
            .width(if (small) 2.dp else 3.dp)
            .height(if (small) 8.dp else 14.dp)
            .background(color)
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    label: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true
) {
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 52.dp)
            .combinedClickable(enabled = true, onClick = onClick, onLongClick = onLongClick, onClickLabel = description)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = if (enabled) Color.White else Color(0x66FFFFFF), modifier = Modifier.size(22.dp))
            if (label != null) {
                Text(label, style = ChaosType.Label, color = Color.White, maxLines = 1, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun GestureBadge(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 96.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Row(
            modifier = Modifier
                .background(Color(0xCC000000), RoundedCornerShape(ChaosShape.BUTTON.dp))
                .padding(horizontal = ChaosSpacing.L.dp, vertical = ChaosSpacing.S.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(ChaosSpacing.S.dp))
            }
            Text(text = text, color = Color.White, style = ChaosType.Heading)
        }
    }
}

@Composable
private fun Toast(text: String) {
    Box(Modifier.fillMaxSize().padding(bottom = 140.dp), contentAlignment = Alignment.BottomCenter) {
        Text(
            text,
            color = Color.White,
            style = ChaosType.Body,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .background(Color(0xE6202020), RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

/**
 * Double-tap skip feedback (spec 4.2 "Ripple + -10 s label"): a soft
 * half-disc ripple from the tapped edge with chevrons and the running
 * total, which grows with each quick extra tap.
 */
@Composable
private fun SkipRipple(skip: com.chaoswares.chaosplay.player.SkipAccumulator.Skip) {
    val ripple = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(skip.count) {
        ripple.snapTo(0f)
        ripple.animateTo(1f, tween(450))
    }
    val chevron = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(skip.count) {
        chevron.snapTo(0f)
        chevron.animateTo(1f, tween(380))
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth * 0.42f
        Box(
            modifier = Modifier
                .align(if (skip.forward) Alignment.CenterEnd else Alignment.CenterStart)
                .width(w)
                .fillMaxSize()
                .clip(
                    if (skip.forward) RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
                    else RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
                )
                .background(Color.White.copy(alpha = 0.10f + 0.10f * (1f - ripple.value))),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row {
                    repeat(3) { i ->
                        val phase = ((chevron.value * 3f) - i).coerceIn(0f, 1f)
                        Icon(
                            if (skip.forward) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.35f + 0.65f * (1f - kotlin.math.abs(phase - 0.5f) * 2f)),
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
                Text(
                    com.chaoswares.chaosplay.player.SkipAccumulator.label(skip),
                    color = Color.White,
                    style = ChaosType.Body,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.graphicsLayer {
                        val pop = 1f + 0.15f * (1f - ripple.value)
                        scaleX = pop; scaleY = pop
                    }
                )
            }
        }
    }
}

/** Big icon pulse after a centre double-tap (spec 4.2 "Icon pulse"). */
@Composable
private fun PlayPausePulse(pulse: Int, playing: Boolean) {
    if (pulse == 0) return
    val anim = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(pulse) {
        anim.snapTo(0f)
        anim.animateTo(1f, tween(550))
    }
    if (anim.value >= 1f) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(88.dp)
                .graphicsLayer {
                    val sc = 0.8f + 0.5f * anim.value
                    scaleX = sc; scaleY = sc
                    alpha = 1f - anim.value
                }
                .background(Color(0x99000000), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (playing) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(52.dp)
            )
        }
    }
}

/** One coloured ripple where a finger landed on the locked screen. */
data class TouchEffect(val at: Offset, val id: Long)

private val KidsColors = listOf(
    Color(0xFFFF6B6B), Color(0xFFFFD93D), Color(0xFF6BCB77), Color(0xFF4D96FF), Color(0xFFB983FF), Color(0xFFFF9F45)
)

@Composable
private fun TouchEffectsLayer(effects: List<TouchEffect>) {
    Box(Modifier.fillMaxSize()) {
        effects.forEach { e ->
            key(e.id) {
                val anim = remember { androidx.compose.animation.core.Animatable(0f) }
                LaunchedEffect(Unit) { anim.animateTo(1f, tween(700)) }
                val color = KidsColors[((e.id % KidsColors.size).toInt() + KidsColors.size) % KidsColors.size]
                // Animated in the draw phase only: no recomposition per frame
                Box(
                    Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = "Touch effect" }
                        .drawBehind {
                            val p = anim.value
                            if (p < 1f) {
                                drawCircle(
                                    color = color.copy(alpha = 0.55f * (1f - p)),
                                    radius = (24.dp + 64.dp * p).toPx(),
                                    center = e.at
                                )
                            }
                        }
                )
            }
        }
    }
}

@Composable
private fun LevelIndicator(level: Float, isVolume: Boolean, boosted: Boolean = false, percent: Int = (level * 100).toInt()) {
    Box(modifier = Modifier.fillMaxSize().padding(ChaosSpacing.XXL.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(Color(0x99000000), RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Icon(
                imageVector = if (isVolume) Icons.AutoMirrored.Filled.VolumeUp else Icons.Filled.Brightness6,
                contentDescription = null,
                tint = if (boosted) Color(0xFFFF7A45) else Color.White,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.height(ChaosSpacing.S.dp))
            Box(
                modifier = Modifier
                    .width(160.dp)
                    .height(6.dp)
                    .background(Color(0x66FFFFFF), RoundedCornerShape(3.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(level.coerceIn(0f, 1f))
                        .height(6.dp)
                        .background(if (boosted) Color(0xFFFF7A45) else Color.White, RoundedCornerShape(3.dp))
                )
            }
            Spacer(Modifier.height(ChaosSpacing.XS.dp))
            Text(
                text = if (isVolume && boosted) "$percent% boost" else "$percent%",
                color = Color.White,
                style = ChaosType.Caption
            )
        }
    }
}

@Composable
private fun LockOverlay(completedCorners: Int) {
    Box(modifier = Modifier.fillMaxSize()) {
        CornerMark(Alignment.TopStart, completedCorners >= 1)
        CornerMark(Alignment.TopEnd, completedCorners >= 2)
        CornerMark(Alignment.BottomEnd, completedCorners >= 3)
        CornerMark(Alignment.BottomStart, completedCorners >= 4)
        Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.LockOpen, contentDescription = "Locked", tint = Color(0x66FFFFFF), modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(ChaosSpacing.S.dp))
            Text("Touch the four corners clockwise to unlock", color = Color(0x88FFFFFF), style = ChaosType.Caption)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.CornerMark(alignment: Alignment, completed: Boolean) {
    val color by androidx.compose.animation.animateColorAsState(
        if (completed) ChaosColors.Success.copy(alpha = 0.6f) else Color(0x22FFFFFF),
        tween(220),
        label = "corner"
    )
    val scale by animateFloatAsState(if (completed) 1.15f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.45f), label = "cornerPop")
    Box(
        modifier = Modifier
            .align(alignment)
            .padding(ChaosSpacing.XL.dp)
            .size(48.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(color, RoundedCornerShape(ChaosShape.BUTTON.dp))
    )
}

@Composable
private fun ErrorOverlay(message: String, onRetry: () -> Unit, onBack: () -> Unit, canNext: Boolean, onNext: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text("Cannot play this", color = Color.White, style = ChaosType.Heading)
            Spacer(Modifier.height(ChaosSpacing.S.dp))
            Text(message, color = ChaosColors.TextMuted, style = ChaosType.Body, textAlign = TextAlign.Center)
            Spacer(Modifier.height(ChaosSpacing.L.dp))
            Row {
                TextButton(onClick = onRetry) { Text("Try again", color = ChaosColors.Accent) }
                if (canNext) TextButton(onClick = onNext) { Text("Next", color = ChaosColors.Accent) }
                TextButton(onClick = onBack) { Text("Go back", color = ChaosColors.Accent) }
            }
        }
    }
}
