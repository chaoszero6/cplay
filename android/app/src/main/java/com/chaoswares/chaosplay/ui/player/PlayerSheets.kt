package com.chaoswares.chaosplay.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chaoswares.chaosplay.player.AspectMode
import com.chaoswares.chaosplay.player.AudioRules
import com.chaoswares.chaosplay.player.Bookmark
import com.chaoswares.chaosplay.player.PlayQueue
import com.chaoswares.chaosplay.player.PlaybackRules
import com.chaoswares.chaosplay.player.RepeatMode
import com.chaoswares.chaosplay.player.SleepTimer
import com.chaoswares.chaosplay.player.TimeFormat
import com.chaoswares.chaosplay.player.VideoTransform
import com.chaoswares.chaosplay.subtitles.SubtitleEncoding
import com.chaoswares.chaosplay.subtitles.SubtitleStyle
import com.chaoswares.chaosplay.subtitles.SubtitleTimeline
import com.chaoswares.chaosplay.ui.theme.ChaosColors
import com.chaoswares.chaosplay.ui.theme.ChaosShape
import com.chaoswares.chaosplay.ui.theme.ChaosSpacing
import com.chaoswares.chaosplay.ui.theme.ChaosType

@Composable
private fun SheetTitle(text: String, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ChaosSpacing.L.dp, vertical = ChaosSpacing.XS.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = ChaosType.Heading, color = ChaosColors.Text, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun SectionCaption(text: String) {
    Text(
        text,
        style = ChaosType.Caption,
        color = ChaosColors.TextMuted,
        modifier = Modifier.padding(start = ChaosSpacing.L.dp, top = ChaosSpacing.M.dp, bottom = ChaosSpacing.XS.dp)
    )
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = ChaosColors.Accent.copy(alpha = 0.22f),
            selectedLabelColor = ChaosColors.Accent,
            labelColor = ChaosColors.Text,
            containerColor = ChaosColors.Surface2
        ),
        border = null
    )
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = ChaosSpacing.L.dp),
        horizontalArrangement = Arrangement.spacedBy(ChaosSpacing.S.dp)
    ) { content() }
}

@Composable
private fun DoneButton(onDismiss: () -> Unit) {
    TextButton(onClick = onDismiss, modifier = Modifier.padding(horizontal = ChaosSpacing.L.dp)) {
        Text("Done", color = ChaosColors.Accent)
    }
}

@Composable
private fun TrackRow(selected: Boolean, title: String, detail: String, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ChaosSpacing.L.dp, vertical = ChaosSpacing.XS.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = ChaosColors.Accent, unselectedColor = ChaosColors.TextMuted)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = ChaosType.Body, color = ChaosColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank()) Text(detail, style = ChaosType.Caption, color = ChaosColors.TextMuted, maxLines = 1)
        }
        trailing()
    }
}

/**
 * Subtitle menu, spec 4.3:
 * ```
 * Subtitles                              [Off ◯]
 * Language: tracks by readable name, Add subtitle file…
 * Second subtitle        [ None ▾ ]
 * Delay                  [ − ]  0.0 s  [ + ]
 * Style                  [ Size ● Color ● Background ]
 * Encoding               [ Auto ▾ ]
 * ```
 * The sheet stays open after a selection so the user sees the result.
 */
@Composable
fun SubtitleSheet(
    state: PlayerUiState,
    onSelectTrack: (String?) -> Unit,
    onSelectExternal: (String) -> Unit,
    onSelectSecondary: (String?) -> Unit,
    onAddFile: () -> Unit,
    onDelay: (Long) -> Unit,
    onResetDelay: () -> Unit,
    onSpeed: (Double) -> Unit,
    onEncoding: (SubtitleEncoding) -> Unit,
    onStyle: (SubtitleStyle) -> Unit,
    onDismiss: () -> Unit
) {
    val off = state.subtitlesOff
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 620.dp)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = ChaosSpacing.L.dp)
    ) {
        SheetTitle("Subtitles") {
            Text("On", style = ChaosType.Body, color = ChaosColors.TextMuted)
            Spacer(Modifier.width(ChaosSpacing.S.dp))
            Switch(
                checked = !off,
                onCheckedChange = { on ->
                    if (!on) onSelectTrack(null)
                    else {
                        val first = state.subtitleTracks.firstOrNull()?.id
                        if (first != null) onSelectTrack(first)
                        else state.externalSubtitles.firstOrNull()?.let { onSelectExternal(it.key) }
                    }
                },
                modifier = Modifier.semantics { contentDescription = "Subtitles on" }
            )
        }
        HorizontalDivider(color = ChaosColors.Surface2)
        SectionCaption("Language")

        TrackRow(selected = off, title = "Off", detail = "", onClick = { onSelectTrack(null) })
        state.subtitleTracks.forEach { track ->
            TrackRow(
                selected = !off && state.primaryExternalKey == null && track.id == state.selectedSubtitleId,
                title = track.displayName,
                detail = "Embedded",
                onClick = { onSelectTrack(track.id) }
            )
        }
        state.externalSubtitles.forEach { ext ->
            val detail = when {
                ext.loading -> "Loading…"
                ext.error != null -> ext.error
                ext.cues != null -> "${ext.cues.size} lines"
                else -> "File"
            }
            TrackRow(
                selected = !off && state.primaryExternalKey == ext.key,
                title = ext.label,
                detail = detail,
                onClick = { onSelectExternal(ext.key) }
            )
        }
        if (state.subtitleTracks.isEmpty() && state.externalSubtitles.isEmpty()) {
            Text(
                "No subtitles found for this video. Add a file, or turn on All files access in Settings so files next to your videos load automatically.",
                style = ChaosType.Caption,
                color = ChaosColors.TextMuted,
                modifier = Modifier.padding(horizontal = ChaosSpacing.L.dp, vertical = ChaosSpacing.S.dp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onAddFile)
                .padding(horizontal = ChaosSpacing.L.dp, vertical = ChaosSpacing.M.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = ChaosColors.Accent)
            Spacer(Modifier.width(ChaosSpacing.M.dp))
            Text("Add subtitle file…", style = ChaosType.Body, color = ChaosColors.Accent)
        }

        if (state.externalSubtitles.size >= 1) {
            HorizontalDivider(color = ChaosColors.Surface2)
            SectionCaption("Second subtitle (shown above the first)")
            ChipRow {
                Chip("None", state.secondaryExternalKey == null) { onSelectSecondary(null) }
                state.externalSubtitles.filter { it.key != state.primaryExternalKey }.forEach { ext ->
                    Chip(ext.label.take(28), state.secondaryExternalKey == ext.key) { onSelectSecondary(ext.key) }
                }
            }
        }

        HorizontalDivider(color = ChaosColors.Surface2, modifier = Modifier.padding(top = ChaosSpacing.S.dp))
        val externalActive = state.primaryExternalKey != null || state.secondaryExternalKey != null
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ChaosSpacing.L.dp, vertical = ChaosSpacing.XS.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Delay", style = ChaosType.Body, color = ChaosColors.Text)
                Text(
                    if (externalActive) "+ shows text later" else "Applies to subtitle files",
                    style = ChaosType.Caption,
                    color = ChaosColors.TextMuted
                )
            }
            TextButton(onClick = { onDelay(-SubtitleTimeline.DELAY_STEP_MS) }, enabled = externalActive) { Text("−", style = ChaosType.Heading) }
            Text(
                SubtitleTimeline.formatDelay(state.subtitleDelayMs),
                style = ChaosType.Body,
                color = ChaosColors.Text,
                modifier = Modifier
                    .clickable(onClick = onResetDelay)
                    .semantics { contentDescription = "Subtitle delay" }
            )
            TextButton(onClick = { onDelay(SubtitleTimeline.DELAY_STEP_MS) }, enabled = externalActive) { Text("+", style = ChaosType.Heading) }
        }

        SectionCaption("Speed (frame-rate fix)")
        ChipRow {
            SubtitleTimeline.SPEED_PRESETS.forEach { (label, value) ->
                Chip(label, kotlin.math.abs(state.subtitleSpeed - value) < 0.0001) { onSpeed(value) }
            }
        }

        SectionCaption("Size")
        val style = state.subtitleStyle
        ChipRow {
            SubtitleStyle.SIZE_PRESETS.forEach { size ->
                Chip("$size", style.sizeSp == size) { onStyle(style.copy(sizeSp = size)) }
            }
        }
        SectionCaption("Colour and look")
        ChipRow {
            SubtitleStyle.COLOR_PRESETS.forEach { (name, argb) ->
                FilterChip(
                    selected = style.colorArgb == argb,
                    onClick = { onStyle(style.copy(colorArgb = argb)) },
                    label = { Text(name) },
                    leadingIcon = { Box(Modifier.size(12.dp).background(Color(argb), CircleShape)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ChaosColors.Accent.copy(alpha = 0.22f),
                        labelColor = ChaosColors.Text,
                        selectedLabelColor = ChaosColors.Accent,
                        containerColor = ChaosColors.Surface2
                    ),
                    border = null
                )
            }
        }
        Spacer(Modifier.height(ChaosSpacing.XS.dp))
        ChipRow {
            Chip("Background box", style.background) { onStyle(style.copy(background = !style.background)) }
            Chip("Outline / shadow", style.outline) { onStyle(style.copy(outline = !style.outline)) }
            Chip("Bold", style.bold) { onStyle(style.copy(bold = !style.bold)) }
        }
        SectionCaption("Position: ${style.bottomMarginPercent}% from the bottom (or drag the text)")
        Slider(
            value = style.bottomMarginPercent.toFloat(),
            onValueChange = { onStyle(style.copy(bottomMarginPercent = it.toInt())) },
            valueRange = 0f..SubtitleStyle.MAX_MARGIN.toFloat(),
            modifier = Modifier.padding(horizontal = ChaosSpacing.L.dp)
        )

        SectionCaption("Encoding (subtitle files)")
        ChipRow {
            SubtitleEncoding.entries.forEach { enc ->
                Chip(enc.label, state.subtitleEncoding == enc) { onEncoding(enc) }
            }
        }
        Spacer(Modifier.height(ChaosSpacing.S.dp))
        DoneButton(onDismiss)
    }
}

/** Audio menu, spec 4.4: tracks with language/codec/channels, volume boost, equalizer. */
@Composable
fun AudioSheet(
    state: PlayerUiState,
    onSelect: (String) -> Unit,
    onBoost: (Float) -> Unit,
    onPreset: (AudioRules.EqPreset) -> Unit,
    onDelay: (Long) -> Unit,
    onResetDelay: () -> Unit,
    onLevelling: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = ChaosSpacing.L.dp)
    ) {
        SheetTitle("Audio")
        HorizontalDivider(color = ChaosColors.Surface2)
        SectionCaption("Track")
        if (state.audioTracks.isEmpty()) {
            Text(
                "No audio track information yet.",
                style = ChaosType.Body,
                color = ChaosColors.TextMuted,
                modifier = Modifier.padding(horizontal = ChaosSpacing.L.dp)
            )
        }
        state.audioTracks.forEach { track ->
            TrackRow(selected = track.id == state.selectedAudioId, title = track.displayName, detail = track.label, onClick = { onSelect(track.id) })
        }
        if (state.audioDecoder.isNotBlank()) {
            Text(
                "Decoder: " + audioDecoderLabel(state.audioDecoder, state.audioMime),
                style = ChaosType.Caption,
                color = ChaosColors.TextMuted,
                modifier = Modifier.padding(horizontal = ChaosSpacing.L.dp, vertical = ChaosSpacing.XS.dp)
            )
        }
        HorizontalDivider(color = ChaosColors.Surface2, modifier = Modifier.padding(top = ChaosSpacing.S.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ChaosSpacing.L.dp, vertical = ChaosSpacing.XS.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Audio delay", style = ChaosType.Body, color = ChaosColors.Text)
                Text("+ plays the sound later (lip sync)", style = ChaosType.Caption, color = ChaosColors.TextMuted)
            }
            TextButton(onClick = { onDelay(-com.chaoswares.chaosplay.network.AvSync.STEP_MS) }) { Text("−", style = ChaosType.Heading) }
            Text(
                com.chaoswares.chaosplay.network.AvSync.format(state.audioDelayMs),
                style = ChaosType.Body,
                color = ChaosColors.Text,
                modifier = Modifier
                    .clickable(onClick = onResetDelay)
                    .semantics { contentDescription = "Audio delay" }
            )
            TextButton(onClick = { onDelay(com.chaoswares.chaosplay.network.AvSync.STEP_MS) }) { Text("+", style = ChaosType.Heading) }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = state.levellingAvailable) { onLevelling(!state.levelling) }
                .padding(horizontal = ChaosSpacing.L.dp, vertical = ChaosSpacing.XS.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Volume levelling", style = ChaosType.Body, color = ChaosColors.Text)
                Text(
                    if (state.levellingAvailable) "Evens out quiet dialogue and loud scenes" else "Needs Android 9 or newer",
                    style = ChaosType.Caption,
                    color = ChaosColors.TextMuted
                )
            }
            Switch(
                checked = state.levelling,
                onCheckedChange = onLevelling,
                enabled = state.levellingAvailable,
                modifier = Modifier.semantics { contentDescription = "Volume levelling" }
            )
        }
        SectionCaption("Volume boost: ${(state.boostLevel * 100).toInt()}%")
        Slider(
            value = state.boostLevel,
            onValueChange = onBoost,
            valueRange = 1f..AudioRules.MAX_BOOST,
            enabled = state.effectsAvailable,
            modifier = Modifier
                .padding(horizontal = ChaosSpacing.L.dp)
                .semantics { contentDescription = "Volume boost" }
        )
        SectionCaption("Equalizer")
        ChipRow {
            AudioRules.EqPreset.entries.forEach { preset ->
                Chip(preset.label, state.eqPreset == preset) { onPreset(preset) }
            }
        }
        if (!state.effectsAvailable) {
            Text(
                "This device does not allow audio effects on video playback.",
                style = ChaosType.Caption,
                color = ChaosColors.TextMuted,
                modifier = Modifier.padding(ChaosSpacing.L.dp)
            )
        }
        DoneButton(onDismiss)
    }
}

/** Speed sheet, spec 4.5: slider 0.25x to 4x, preset chips, reset. */
@Composable
fun SpeedSheet(currentSpeed: Float, onSpeedChange: (Float) -> Unit, onDismiss: () -> Unit) {
    var speed by remember { mutableFloatStateOf(currentSpeed) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(vertical = ChaosSpacing.L.dp)
    ) {
        SheetTitle("Playback speed")
        Text(
            text = TimeFormat.formatSpeed(speed),
            style = ChaosType.Title,
            color = ChaosColors.Accent,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Slider(
            value = speed,
            onValueChange = {
                speed = (Math.round(PlaybackRules.clampSpeed(it) * 20f) / 20f)
                onSpeedChange(speed)
            },
            valueRange = PlaybackRules.MIN_SPEED..PlaybackRules.MAX_SPEED,
            modifier = Modifier.padding(horizontal = ChaosSpacing.L.dp)
        )
        ChipRow {
            listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f).forEach { preset ->
                Chip(TimeFormat.formatSpeed(preset), speed == preset) {
                    speed = preset
                    onSpeedChange(preset)
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ChaosSpacing.S.dp, vertical = ChaosSpacing.S.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = {
                speed = PlaybackRules.DEFAULT_SPEED
                onSpeedChange(PlaybackRules.DEFAULT_SPEED)
            }) { Text("Reset", color = ChaosColors.Text) }
            TextButton(onClick = onDismiss) { Text("Done", color = ChaosColors.Accent) }
        }
    }
}

/** Aspect, zoom, mirror, flip and rotation (spec 3 and 4.5). */
@Composable
fun AspectSheet(
    current: AspectMode,
    transform: VideoTransform,
    onSelect: (AspectMode) -> Unit,
    onZoom: (Float) -> Unit,
    onMirror: () -> Unit,
    onFlip: () -> Unit,
    onRotate: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = ChaosSpacing.L.dp)
    ) {
        SheetTitle("Aspect & zoom")
        SectionCaption("Aspect ratio")
        ChipRow {
            AspectMode.entries.forEach { mode -> Chip(mode.label, mode == current) { onSelect(mode) } }
        }
        SectionCaption("Zoom: ${transform.zoomLabel()} (or pinch)")
        Slider(
            value = transform.zoom,
            onValueChange = onZoom,
            valueRange = VideoTransform.MIN_ZOOM..3f,
            modifier = Modifier.padding(horizontal = ChaosSpacing.L.dp)
        )
        SectionCaption("Transform")
        ChipRow {
            Chip("Mirror", transform.mirrored, onMirror)
            Chip("Upside down", transform.flipped, onFlip)
            Chip("Rotate video ${transform.rotationDegrees}°", transform.rotationDegrees != 0, onRotate)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ChaosSpacing.S.dp, vertical = ChaosSpacing.S.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onReset) { Text("Reset", color = ChaosColors.Text) }
            TextButton(onClick = onDismiss) { Text("Done", color = ChaosColors.Accent) }
        }
    }
}

/** Sleep timer, spec 4.2 overflow entry. */
@Composable
fun SleepSheet(remainingMs: Long?, onStart: (Int) -> Unit, onCancel: () -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = ChaosSpacing.L.dp)
    ) {
        SheetTitle("Sleep timer")
        if (remainingMs != null) {
            Text(
                text = "Pausing in ${SleepTimer.remainingMinutes(remainingMs)} min",
                style = ChaosType.Body,
                color = ChaosColors.Accent2,
                modifier = Modifier.padding(horizontal = ChaosSpacing.L.dp, vertical = ChaosSpacing.XS.dp)
            )
        }
        ChipRow {
            SleepTimer.PRESET_MINUTES.forEach { minutes ->
                AssistChip(
                    onClick = { onStart(minutes); onDismiss() },
                    label = { Text("$minutes min") },
                    colors = AssistChipDefaults.assistChipColors(labelColor = ChaosColors.Text)
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ChaosSpacing.S.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = { onCancel(); onDismiss() }, enabled = remainingMs != null) { Text("Cancel timer") }
            TextButton(onClick = onDismiss) { Text("Done", color = ChaosColors.Accent) }
        }
    }
}

/** Queue sheet, spec 4.7: current item highlighted, reorder, remove; shuffle and loop. */
@Composable
fun QueueSheet(
    queue: PlayQueue,
    onPlay: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = ChaosSpacing.S.dp)
    ) {
        SheetTitle("Queue · ${queue.size}") {
            IconButton(onClick = onShuffle) {
                Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle", tint = if (queue.shuffle) ChaosColors.Accent else ChaosColors.TextMuted)
            }
            IconButton(onClick = onRepeat) {
                Icon(
                    if (queue.repeat == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    contentDescription = queue.repeat.label,
                    tint = if (queue.repeat != RepeatMode.OFF) ChaosColors.Accent else ChaosColors.TextMuted
                )
            }
        }
        HorizontalDivider(color = ChaosColors.Surface2)
        LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
            itemsIndexed(queue.items) { index, item ->
                val current = index == queue.index
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (current) ChaosColors.Accent.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable { onPlay(index) }
                        .padding(start = ChaosSpacing.L.dp, end = ChaosSpacing.XS.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (current) Icon(Icons.Filled.PlayArrow, contentDescription = "Now playing", tint = ChaosColors.Accent, modifier = Modifier.size(18.dp))
                    else Text("${index + 1}", style = ChaosType.Caption, color = ChaosColors.TextMuted, modifier = Modifier.width(18.dp))
                    Spacer(Modifier.width(ChaosSpacing.S.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.title.ifBlank { item.uri.substringAfterLast('/') },
                            style = ChaosType.Body,
                            color = if (current) ChaosColors.Accent else ChaosColors.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (item.durationMs > 0) Text(TimeFormat.format(item.durationMs), style = ChaosType.Caption, color = ChaosColors.TextMuted)
                    }
                    IconButton(onClick = { onMove(index, index - 1) }, enabled = index > 0) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up", tint = ChaosColors.TextMuted)
                    }
                    IconButton(onClick = { onMove(index, index + 1) }, enabled = index < queue.items.lastIndex) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down", tint = ChaosColors.TextMuted)
                    }
                    IconButton(onClick = { onRemove(index) }, enabled = queue.size > 1) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove from queue", tint = ChaosColors.TextMuted)
                    }
                }
            }
        }
        DoneButton(onDismiss)
    }
}

/** Bookmarks, spec 3: "Bookmarks / favorite points in a video". */
@Composable
fun BookmarksSheet(
    bookmarks: List<Bookmark>,
    positionMs: Long,
    onAdd: () -> Unit,
    onJump: (Bookmark) -> Unit,
    onRemove: (Bookmark) -> Unit,
    onRename: (Bookmark, String) -> Unit,
    onDismiss: () -> Unit
) {
    var renaming by remember { mutableStateOf<Bookmark?>(null) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = ChaosSpacing.S.dp)
    ) {
        SheetTitle("Bookmarks") {
            TextButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = ChaosColors.Accent)
                Spacer(Modifier.width(4.dp))
                Text("Add at ${TimeFormat.format(positionMs)}", color = ChaosColors.Accent)
            }
        }
        HorizontalDivider(color = ChaosColors.Surface2)
        if (bookmarks.isEmpty()) {
            Text(
                "No bookmarks in this video yet.",
                style = ChaosType.Body,
                color = ChaosColors.TextMuted,
                modifier = Modifier.padding(ChaosSpacing.L.dp)
            )
        }
        LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
            itemsIndexed(bookmarks) { _, bm ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onJump(bm) }
                        .padding(start = ChaosSpacing.L.dp, end = ChaosSpacing.XS.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Bookmark, contentDescription = null, tint = ChaosColors.Accent2, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(ChaosSpacing.M.dp))
                    Column(Modifier.weight(1f)) {
                        Text(bm.label, style = ChaosType.Body, color = ChaosColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(TimeFormat.format(bm.positionMs), style = ChaosType.Caption, color = ChaosColors.TextMuted)
                    }
                    IconButton(onClick = { renaming = bm }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Rename bookmark", tint = ChaosColors.TextMuted)
                    }
                    IconButton(onClick = { onRemove(bm) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete bookmark", tint = ChaosColors.TextMuted)
                    }
                }
            }
        }
        DoneButton(onDismiss)
    }
    renaming?.let { bm ->
        var text by remember(bm) { mutableStateOf(bm.label) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = ChaosColors.Surface,
            title = { Text("Rename bookmark", color = ChaosColors.Text) },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { onRename(bm, text); renaming = null }) { Text("Save", color = ChaosColors.Accent) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } }
        )
    }
}

/** Playback info, spec 3 "Media info screen (codec, resolution, bitrate, active decoder)". */
@Composable
fun PlaybackInfoDialog(state: PlayerUiState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ChaosColors.Surface,
        shape = RoundedCornerShape(ChaosShape.CARD.dp),
        title = { Text("Playback info", style = ChaosType.Heading, color = ChaosColors.Text) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(ChaosSpacing.S.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                InfoRow("Title", state.title.ifBlank { "Playing" })
                InfoRow("Source", state.sourceLabel)
                InfoRow("Resolution", if (state.videoWidth > 0) "${state.videoWidth}×${state.videoHeight}" else "audio only / unknown")
                if (state.videoBitrate > 0) InfoRow("Video bitrate", "${state.videoBitrate / 1000} kbps")
                InfoRow("Buffered", TimeFormat.format((state.bufferedMs - state.positionMs).coerceAtLeast(0)) + " ahead")
                InfoRow("Decoder", "${state.decoderMode.label} · ${state.decoderDetail}")
                InfoRow("Active codec", state.activeCodecName?.takeIf { it.isNotBlank() } ?: "initialising…")
                if (state.audioDecoder.isNotBlank()) InfoRow("Audio decoder", audioDecoderLabel(state.audioDecoder, state.audioMime))
                if (state.audioDelayMs != 0L) InfoRow("Audio delay", com.chaoswares.chaosplay.network.AvSync.format(state.audioDelayMs))
                InfoRow("Speed", TimeFormat.formatSpeed(state.speed))
                InfoRow("Aspect", state.aspectMode.label)
                InfoRow("Tracks", "${state.audioTracks.size} audio · ${state.subtitleTracks.size} embedded subtitles · ${state.externalSubtitles.size} subtitle files")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = ChaosColors.Accent) } }
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(label, style = ChaosType.Caption, color = ChaosColors.TextMuted)
        Text(value, style = ChaosType.Body, color = ChaosColors.Text)
    }
}

/** "FFmpeg · AC3" for the bundled decoder, "Device · AAC (c2.android.aac.decoder)" otherwise. */
fun audioDecoderLabel(decoder: String, mime: String): String {
    val codec = when {
        mime.contains("eac3") || mime.contains("ec-3") -> "E-AC3"
        mime.contains("ac3") -> "AC3"
        mime.contains("dts") -> "DTS"
        mime.contains("true-hd") || mime.contains("truehd") -> "TrueHD"
        mime.contains("mp4a") || mime.contains("aac") -> "AAC"
        mime.isNotBlank() -> mime.substringAfter('/').uppercase()
        else -> "audio"
    }
    return if (decoder.contains("ffmpeg", ignoreCase = true)) "FFmpeg · $codec" else "Device · $codec ($decoder)"
}

/**
 * "Play on TV" picker (spec 19.4 / 19.5): DLNA TVs and Chromecasts on the
 * same Wi-Fi. Scanning runs while the sheet is open.
 */
@Composable
fun TvSheet(
    devices: List<com.chaoswares.chaosplay.playback.TvDevice>,
    scanning: Boolean,
    castAvailable: Boolean,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onPick: (com.chaoswares.chaosplay.playback.TvDevice) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.runtime.DisposableEffect(Unit) {
        onScan()
        onDispose { onStopScan() }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = ChaosSpacing.L.dp)
    ) {
        SheetTitle("Play on TV") {
            if (scanning) androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = ChaosColors.Accent)
            else TextButton(onClick = onScan) { Text("Search again", color = ChaosColors.Accent) }
        }
        HorizontalDivider(color = ChaosColors.Surface2)
        if (devices.isEmpty()) {
            Text(
                if (scanning) "Looking for TVs on this Wi-Fi…"
                else "No TV found. Turn the TV on, connect it to the same Wi-Fi as this phone, and on Samsung/LG TVs allow " +
                    "phone connections (DLNA / screen sharing) in the TV's settings.",
                style = ChaosType.Body,
                color = ChaosColors.TextMuted,
                modifier = Modifier.padding(ChaosSpacing.L.dp)
            )
        }
        devices.forEach { d ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(d) }
                    .padding(horizontal = ChaosSpacing.L.dp, vertical = ChaosSpacing.M.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (d.kind == com.chaoswares.chaosplay.playback.TvDevice.Kind.CAST) Icons.Filled.Cast else Icons.Filled.Tv,
                    contentDescription = null,
                    tint = ChaosColors.Accent
                )
                Spacer(Modifier.width(ChaosSpacing.M.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.name, style = ChaosType.Body, color = ChaosColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${d.kind.label} · ${d.detail}", style = ChaosType.Caption, color = ChaosColors.TextMuted, maxLines = 1)
                }
            }
        }
        if (!castAvailable) {
            Text(
                "Chromecast needs Google Play services; DLNA TVs work without it.",
                style = ChaosType.Caption,
                color = ChaosColors.TextMuted,
                modifier = Modifier.padding(horizontal = ChaosSpacing.L.dp, vertical = ChaosSpacing.XS.dp)
            )
        }
        DoneButton(onDismiss)
    }
}

/** Remote control while the video plays on a TV. */
@Composable
fun TvRemoteOverlay(
    tv: TvUi,
    title: String,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkip: (Long) -> Unit,
    onStop: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF2000000))
            .semantics { contentDescription = "TV remote" },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(ChaosSpacing.XL.dp)) {
            Icon(Icons.Filled.Tv, contentDescription = null, tint = ChaosColors.Accent, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(ChaosSpacing.M.dp))
            Text("Playing on ${tv.deviceName}", style = ChaosType.Heading, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(title, style = ChaosType.Body, color = ChaosColors.TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(ChaosSpacing.S.dp))
            Text(
                when {
                    tv.error != null -> tv.error
                    tv.connecting -> "Connecting to the TV…"
                    tv.state == "TRANSITIONING" -> "Loading on the TV…"
                    tv.playing -> "Playing"
                    tv.state == "STOPPED" -> "Stopped"
                    else -> "Paused"
                },
                style = ChaosType.Caption,
                color = if (tv.error != null) ChaosColors.Danger else ChaosColors.Accent2
            )
            if (tv.connecting) {
                Spacer(Modifier.height(ChaosSpacing.M.dp))
                androidx.compose.material3.CircularProgressIndicator(color = Color.White)
            } else {
                Spacer(Modifier.height(ChaosSpacing.L.dp))
                var drag by remember { mutableStateOf<Float?>(null) }
                val fraction = drag ?: if (tv.durationMs > 0) (tv.positionMs.toFloat() / tv.durationMs).coerceIn(0f, 1f) else 0f
                Slider(
                    value = fraction,
                    onValueChange = { drag = it },
                    onValueChangeFinished = {
                        drag?.let { f -> onSeek((f * tv.durationMs).toLong()) }
                        drag = null
                    },
                    enabled = tv.durationMs > 0,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "TV seek bar" }
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(TimeFormat.format((fraction * tv.durationMs).toLong()), color = Color.White, style = ChaosType.Caption)
                    Text(TimeFormat.format(tv.durationMs), color = Color.White, style = ChaosType.Caption)
                }
                Spacer(Modifier.height(ChaosSpacing.M.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onSkip(-10_000) }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Filled.Replay10, contentDescription = "TV back 10 s", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                    Spacer(Modifier.width(ChaosSpacing.L.dp))
                    IconButton(
                        onClick = onPlayPause,
                        modifier = Modifier
                            .size(72.dp)
                            .background(ChaosColors.Accent, CircleShape)
                    ) {
                        Icon(
                            if (tv.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (tv.playing) "Pause TV" else "Play TV",
                            tint = Color.Black,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    Spacer(Modifier.width(ChaosSpacing.L.dp))
                    IconButton(onClick = { onSkip(10_000) }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Filled.Forward10, contentDescription = "TV forward 10 s", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                }
            }
            Spacer(Modifier.height(ChaosSpacing.XL.dp))
            androidx.compose.material3.OutlinedButton(onClick = onStop) {
                Text("Stop and continue on phone", color = Color.White)
            }
        }
    }
}
