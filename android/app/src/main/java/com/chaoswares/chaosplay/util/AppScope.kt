package com.chaoswares.chaosplay.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-lifetime scope for work that must finish after a screen is gone:
 * the final resume-position save and the Jellyfin "stopped" report run here
 * because the player's view model scope is cancelled the moment it clears.
 */
val AppScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
