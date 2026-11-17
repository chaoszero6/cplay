package com.chaoswares.chaosplay.ui

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.chaoswares.chaosplay.ui.theme.ChaosType
import com.chaoswares.chaosplay.ui.theme.chaosTokens

/**
 * The four bottom navigation destinations, spec section 3.1:
 * Library, Network, Playlists / History, Settings.
 * "The player is a full-screen layer above everything."
 */
enum class MainTab(val label: String, val shortLabel: String, val icon: ImageVector) {
    LIBRARY("Library", "Library", Icons.Filled.VideoLibrary),
    NETWORK("Network", "Network", Icons.Filled.Dns),
    PLAYLISTS("Playlists / History", "Playlists", Icons.Filled.DateRange),
    SETTINGS("Settings", "Settings", Icons.Filled.Settings)
}

/**
 * Scaffold hosting the four tabs. The player is deliberately NOT a
 * destination here - it is pushed over the whole scaffold so no navigation
 * bar steals space from the video (spec 3.1).
 */
@Composable
fun MainScaffold(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    libraryContent: @Composable () -> Unit,
    networkContent: @Composable () -> Unit,
    playlistsContent: @Composable () -> Unit,
    settingsContent: @Composable () -> Unit
) {
    val tokens = chaosTokens()

    Scaffold(
        containerColor = tokens.bg,
        bottomBar = {
            NavigationBar(
                containerColor = tokens.surface,
                contentColor = tokens.text
            ) {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == selected,
                        onClick = { onSelect(tab) },
                        icon = {
                            Icon(
                                tab.icon,
                                contentDescription = tab.label
                            )
                        },
                        label = {
                            Text(
                                text = tab.shortLabel,
                                style = ChaosType.Label,
                                maxLines = 1
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = tokens.accent,
                            selectedTextColor = tokens.accent,
                            indicatorColor = tokens.surface2,
                            unselectedIconColor = tokens.textMuted,
                            unselectedTextColor = tokens.textMuted
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // Tabs cross-fade (spec 2.5: short motion, 150-250 ms)
            androidx.compose.animation.AnimatedContent(
                targetState = selected,
                transitionSpec = {
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180, delayMillis = 40))
                        .togetherWith(androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)))
                },
                label = "tabs"
            ) { tab ->
                when (tab) {
                    MainTab.LIBRARY -> libraryContent()
                    MainTab.NETWORK -> networkContent()
                    MainTab.PLAYLISTS -> playlistsContent()
                    MainTab.SETTINGS -> settingsContent()
                }
            }
        }
    }
}
