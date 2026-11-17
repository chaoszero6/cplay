package com.chaoswares.chaosplay.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chaoswares.chaosplay.data.VideoItem
import com.chaoswares.chaosplay.player.LibraryTabs
import com.chaoswares.chaosplay.player.TimeFormat
import com.chaoswares.chaosplay.ui.theme.ChaosShape
import com.chaoswares.chaosplay.ui.theme.ChaosSpacing
import com.chaoswares.chaosplay.ui.theme.ChaosType
import com.chaoswares.chaosplay.ui.theme.chaosTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Long-press menu, spec section 4.1: "long press or right-click opens:
 * Play next, Add to playlist, Info, Rename, Share, Delete, Hide".
 *
 * Actions that need a feature which is not built yet are shown but marked,
 * rather than silently doing nothing.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ItemActionSheet(
    video: VideoItem,
    isFavourite: Boolean,
    onDismiss: () -> Unit,
    onAction: (LibraryTabs.ItemAction) -> Unit
) {
    val tokens = chaosTokens()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = tokens.surface
    ) {
        Column(modifier = Modifier.padding(bottom = ChaosSpacing.XL.dp)) {
            Text(
                text = video.title,
                style = ChaosType.Heading,
                color = tokens.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = ChaosSpacing.L.dp)
            )
            Spacer(Modifier.height(ChaosSpacing.S.dp))
            HorizontalDivider(color = tokens.surface2)

            LibraryTabs.menuFor(isFavourite).forEach { action ->
                ActionRow(
                    label = if (action == LibraryTabs.ItemAction.FAVORITES_TOGGLE) {
                        LibraryTabs.favouriteLabel(isFavourite)
                    } else {
                        action.label
                    },
                    icon = iconFor(action),
                    available = action in IMPLEMENTED_ACTIONS,
                    danger = action == LibraryTabs.ItemAction.DELETE,
                    onClick = { onAction(action) }
                )
            }
        }
    }
}

/** Actions wired to real behaviour in this build. */
private val IMPLEMENTED_ACTIONS = LibraryTabs.ItemAction.entries.toSet()

private fun iconFor(action: LibraryTabs.ItemAction): ImageVector = when (action) {
    LibraryTabs.ItemAction.PLAY -> Icons.Filled.PlayArrow
    LibraryTabs.ItemAction.FAVORITES_TOGGLE -> Icons.Filled.Favorite
    LibraryTabs.ItemAction.PLAY_NEXT -> Icons.Filled.PlayArrow
    LibraryTabs.ItemAction.ADD_TO_PLAYLIST -> Icons.Filled.Add
    LibraryTabs.ItemAction.INFO -> Icons.Filled.Info
    LibraryTabs.ItemAction.RENAME -> Icons.Filled.Edit
    LibraryTabs.ItemAction.SHARE -> Icons.Filled.Share
    LibraryTabs.ItemAction.DELETE -> Icons.Filled.Delete
    LibraryTabs.ItemAction.HIDE -> Icons.Filled.VisibilityOff
}

@Composable
private fun ActionRow(
    label: String,
    icon: ImageVector,
    available: Boolean,
    danger: Boolean,
    onClick: () -> Unit
) {
    val tokens = chaosTokens()
    val tint = when {
        danger -> tokens.danger
        available -> tokens.text
        else -> tokens.textMuted
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = available, onClick = onClick)
            .padding(horizontal = ChaosSpacing.L.dp, vertical = ChaosSpacing.M.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(ChaosSpacing.M.dp))
        Text(label, style = ChaosType.Body, color = tint, modifier = Modifier.weight(1f))
        if (!available) {
            Text("Soon", style = ChaosType.Caption, color = tokens.textMuted)
        }
    }
}

/** File details dialog, the "Info" entry from the long-press menu. */
@Composable
fun InfoDialog(video: VideoItem, onDismiss: () -> Unit) {
    val tokens = chaosTokens()
    val added = if (video.dateAddedSec > 0L) {
        SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
            .format(Date(video.dateAddedSec * 1000L))
    } else {
        "Unknown"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = tokens.surface,
        shape = RoundedCornerShape(ChaosShape.CARD.dp),
        title = { Text("Info", style = ChaosType.Heading, color = tokens.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ChaosSpacing.S.dp)) {
                InfoLine("Name", video.title)
                InfoLine("Duration", TimeFormat.format(video.durationMs))
                InfoLine("Resolution", video.resolutionLabel.ifBlank { "Unknown" })
                InfoLine("Size", video.sizeLabel)
                InfoLine("Type", video.mimeType.ifBlank { "Unknown" })
                InfoLine("Added", added)
                InfoLine("Path", video.path.ifBlank { video.uri })
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = tokens.accent)
            }
        }
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    val tokens = chaosTokens()
    Column {
        Text(label, style = ChaosType.Caption, color = tokens.textMuted)
        Text(value, style = ChaosType.Body, color = tokens.text)
    }
}
