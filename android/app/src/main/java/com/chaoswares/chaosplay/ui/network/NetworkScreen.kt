package com.chaoswares.chaosplay.ui.network

import androidx.activity.compose.BackHandler
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import com.chaoswares.chaosplay.data.VideoItem
import com.chaoswares.chaosplay.network.JellyfinRules
import com.chaoswares.chaosplay.network.NetHttp
import com.chaoswares.chaosplay.network.RemoteEntry
import com.chaoswares.chaosplay.network.ServerProfile
import com.chaoswares.chaosplay.network.ServerType
import com.chaoswares.chaosplay.network.StreamRules
import com.chaoswares.chaosplay.player.PlayQueue
import com.chaoswares.chaosplay.player.QueueItem
import com.chaoswares.chaosplay.player.TimeFormat
import com.chaoswares.chaosplay.ui.theme.ChaosShape
import com.chaoswares.chaosplay.ui.theme.ChaosSpacing
import com.chaoswares.chaosplay.ui.theme.ChaosType
import com.chaoswares.chaosplay.ui.theme.HeaderTitle
import com.chaoswares.chaosplay.ui.theme.chaosTokens

private fun iconFor(type: ServerType): ImageVector = when (type) {
    ServerType.JELLYFIN -> Icons.Filled.Movie
    ServerType.NEXTCLOUD -> Icons.Filled.Cloud
    ServerType.WEBDAV -> Icons.Filled.Storage
    ServerType.URL -> Icons.Filled.Link
    ServerType.SMB, ServerType.NFS -> Icons.Filled.Storage
    ServerType.FTP, ServerType.SFTP -> Icons.Filled.Folder
    ServerType.DLNA -> Icons.Filled.Wifi
    else -> Icons.Filled.Dns
}

/**
 * Network tab, spec 4.6: saved servers, servers found on the network, the
 * Add server button and the browsers for Jellyfin and Nextcloud/WebDAV.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(viewModel: NetworkViewModel, onPlay: (PlayQueue) -> Unit) {
    val tokens = chaosTokens()
    val stack by viewModel.stack.collectAsState()
    val form by viewModel.form.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var chooser by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val dest = stack.last()
    BackHandler(enabled = stack.size > 1) { viewModel.back() }

    Box(Modifier.fillMaxSize().background(tokens.bg)) {
        // Going into a folder slides left, coming back slides right
        androidx.compose.animation.AnimatedContent(
            targetState = stack.size to dest,
            transitionSpec = {
                val forward = targetState.first >= initialState.first
                val dir = if (forward) 1 else -1
                (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(com.chaoswares.chaosplay.ui.theme.ChaosMotion.SCREEN_MS)) { w -> dir * w / 4 } +
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(com.chaoswares.chaosplay.ui.theme.ChaosMotion.SCREEN_MS)))
                    .togetherWith(
                        androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(com.chaoswares.chaosplay.ui.theme.ChaosMotion.SCREEN_MS)) { w -> -dir * w / 4 } +
                            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150))
                    )
            },
            label = "networkNav"
        ) { (_, d) ->
            when (d) {
                NetDest.Home -> NetworkHome(viewModel, onPlay, onAdd = { chooser = true })
                is NetDest.Dav -> DavBrowser(viewModel, d, onPlay)
                is NetDest.Share -> ShareBrowser(viewModel, d, onPlay)
                is NetDest.Dlna -> DlnaBrowser(viewModel, d, onPlay)
                else -> JellyfinBrowser(viewModel, d, onPlay)
            }
        }
        if (dest == NetDest.Home) {
            ExtendedFloatingActionButton(
                onClick = { chooser = true },
                containerColor = tokens.accent,
                contentColor = Color.Black,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(ChaosSpacing.L.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(ChaosSpacing.S.dp))
                Text("Add server")
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp))
    }

    if (chooser) {
        ModalBottomSheet(onDismissRequest = { chooser = false }, containerColor = tokens.surface) {
            Column(Modifier.navigationBarsPadding().padding(bottom = ChaosSpacing.L.dp)) {
                Text("Add server", style = ChaosType.Heading, color = tokens.text, modifier = Modifier.padding(horizontal = ChaosSpacing.L.dp))
                Spacer(Modifier.height(ChaosSpacing.S.dp))
                ServerType.entries.forEach { type ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = type.available) {
                                chooser = false
                                viewModel.startAdd(type)
                            }
                            .padding(horizontal = ChaosSpacing.L.dp, vertical = ChaosSpacing.M.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(iconFor(type), contentDescription = null, tint = if (type.available) tokens.accent else tokens.textMuted)
                        Spacer(Modifier.width(ChaosSpacing.M.dp))
                        Column(Modifier.weight(1f)) {
                            Text(type.label, style = ChaosType.Body, color = if (type.available) tokens.text else tokens.textMuted)
                            Text(
                                when (type) {
                                    ServerType.JELLYFIN -> "Sign in, browse libraries, resume synced with Jellyfin"
                                    ServerType.NEXTCLOUD -> "Your Nextcloud files, with an app password"
                                    ServerType.WEBDAV -> "Any WebDAV share (NAS, ownCloud, rclone…)"
                                    ServerType.URL -> "HTTP, HLS (.m3u8), DASH (.mpd) or RTSP link"
                                    ServerType.SMB -> "Windows shares, Samba and most NAS boxes (SMB 2/3)"
                                    ServerType.FTP -> "FTP server, user or anonymous"
                                    ServerType.SFTP -> "Files over SSH (any Linux box or NAS with SSH)"
                                    ServerType.NFS -> "NFSv3 export (Linux / NAS); the export must allow this phone"
                                    ServerType.DLNA -> "Media servers on your network (MiniDLNA, Plex/Emby DLNA, TVs, routers)"
                                    else -> "Not available yet"
                                },
                                style = ChaosType.Caption,
                                color = tokens.textMuted
                            )
                        }
                    }
                }
            }
        }
    }

    form?.let { f -> AddServerDialog(f, viewModel, onPlay) }
}

@Composable
private fun NetworkHome(viewModel: NetworkViewModel, onPlay: (PlayQueue) -> Unit, onAdd: () -> Unit) {
    val tokens = chaosTokens()
    val profiles by viewModel.profiles.collectAsState()
    val streams by viewModel.streams.collectAsState()
    val discovered by viewModel.discovered.collectAsState()
    val discoveredDlna by viewModel.discoveredDlna.collectAsState()
    val discovering by viewModel.discovering.collectAsState()
    var link by remember { mutableStateOf("") }
    var linkError by remember { mutableStateOf<String?>(null) }
    var warnInsecure by remember { mutableStateOf<String?>(null) }

    fun playLink() {
        when (val v = StreamRules.validate(link)) {
            is StreamRules.Validation.Invalid -> linkError = v.reason
            is StreamRules.Validation.Valid -> {
                linkError = null
                if (StreamRules.isInsecurePublic(v.url) && warnInsecure != v.url) {
                    warnInsecure = v.url
                } else {
                    onPlay(PlayQueue.single(QueueItem(v.url, StreamRules.displayName(v.url))))
                }
            }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = ChaosSpacing.L.dp, end = ChaosSpacing.L.dp, top = ChaosSpacing.L.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(ChaosSpacing.S.dp),
        modifier = Modifier.statusBarsPadding()
    ) {
        item {
            HeaderTitle("Network", color = tokens.text)
            Text("Stream from Jellyfin, Nextcloud, WebDAV shares or any video link.", style = ChaosType.Caption, color = tokens.textMuted)
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = tokens.surface), shape = RoundedCornerShape(ChaosShape.CARD.dp)) {
                Column(Modifier.padding(ChaosSpacing.M.dp)) {
                    Text("Play a link", style = ChaosType.Body, color = tokens.text)
                    OutlinedTextField(
                        value = link,
                        onValueChange = { link = it; linkError = null },
                        placeholder = { Text("https://…/video.mp4 or .m3u8") },
                        singleLine = true,
                        isError = linkError != null,
                        supportingText = {
                            when {
                                linkError != null -> Text(linkError!!)
                                warnInsecure != null -> Text("This link is not encrypted (http over the internet). Press Play again to continue.")
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { playLink() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Video link" }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(ChaosSpacing.S.dp)) {
                        Button(onClick = { playLink() }, enabled = link.isNotBlank()) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Play")
                        }
                        OutlinedButton(onClick = {
                            when (val v = StreamRules.validate(link)) {
                                is StreamRules.Validation.Invalid -> linkError = v.reason
                                is StreamRules.Validation.Valid -> { viewModel.saveStream("", v.url); link = "" }
                            }
                        }, enabled = link.isNotBlank()) { Text("Save") }
                    }
                }
            }
        }

        item { SectionLabel("Saved servers") }
        if (profiles.isEmpty()) {
            item {
                EmptyCard(Icons.Filled.Dns, "No servers yet", "Add Jellyfin or Nextcloud to stream from your server.", action = "Add server", onAction = onAdd)
            }
        }
        items(profiles, key = { it.id }) { p -> ServerCard(p, viewModel) }

        if (streams.isNotEmpty()) {
            item { SectionLabel("Saved links") }
            items(streams, key = { "s-${it.id}" }) { s ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = tokens.surface),
                    shape = RoundedCornerShape(ChaosShape.CARD.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlay(PlayQueue.single(QueueItem(s.url, s.name))) }
                ) {
                    Row(Modifier.padding(ChaosSpacing.M.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Link, contentDescription = null, tint = tokens.accent)
                        Spacer(Modifier.width(ChaosSpacing.M.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = ChaosType.Body, color = tokens.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${StreamRules.kindOf(s.url).label} · ${StreamRules.redact(s.url)}",
                                style = ChaosType.Caption,
                                color = tokens.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { viewModel.removeStream(s.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove ${s.name}", tint = tokens.textMuted)
                        }
                    }
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionLabel("Found on your network") }
                TextButton(onClick = { viewModel.discover() }, enabled = !discovering) {
                    if (discovering) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Wifi, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(if (discovering) "Searching…" else "Search")
                }
            }
        }
        items(discoveredDlna, key = { "u-${it.location}" }) { d ->
            Card(colors = CardDefaults.cardColors(containerColor = tokens.surface), shape = RoundedCornerShape(ChaosShape.CARD.dp)) {
                Row(Modifier.padding(ChaosSpacing.M.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Wifi, contentDescription = null, tint = tokens.accent)
                    Spacer(Modifier.width(ChaosSpacing.M.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = ChaosType.Body, color = tokens.text)
                        Text("DLNA · ${JellyfinRules.hostOf(d.location)}", style = ChaosType.Caption, color = tokens.textMuted)
                    }
                    Button(onClick = { viewModel.connectDlna(d) }) { Text("Open") }
                }
            }
        }
        if (discovered.isEmpty() && discoveredDlna.isEmpty()) {
            item {
                Text(
                    "Tap Search to look for Jellyfin and DLNA media servers on this Wi-Fi.",
                    style = ChaosType.Caption,
                    color = tokens.textMuted
                )
            }
        }
        items(discovered, key = { "d-${it.address}" }) { d ->
            Card(colors = CardDefaults.cardColors(containerColor = tokens.surface), shape = RoundedCornerShape(ChaosShape.CARD.dp)) {
                Row(Modifier.padding(ChaosSpacing.M.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Movie, contentDescription = null, tint = tokens.accent)
                    Spacer(Modifier.width(ChaosSpacing.M.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = ChaosType.Body, color = tokens.text)
                        Text("Jellyfin · ${d.address}", style = ChaosType.Caption, color = tokens.textMuted)
                    }
                    Button(onClick = { viewModel.startAdd(ServerType.JELLYFIN, d.address) }) { Text("Connect") }
                }
            }
        }
    }
}

@Composable
private fun ServerCard(p: ServerProfile, viewModel: NetworkViewModel) {
    val tokens = chaosTokens()
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = tokens.surface),
        shape = RoundedCornerShape(ChaosShape.CARD.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { viewModel.openProfile(p) }
    ) {
        Row(Modifier.padding(ChaosSpacing.M.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(tokens.surface2),
                contentAlignment = Alignment.Center
            ) {
                Icon(iconFor(p.type), contentDescription = null, tint = tokens.accent)
            }
            Spacer(Modifier.width(ChaosSpacing.M.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, style = ChaosType.Body, color = tokens.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${p.type.label} · ${viewModel.describe(p)}", style = ChaosType.Caption, color = tokens.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Server options", tint = tokens.textMuted) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; viewModel.openProfile(p) })
                    DropdownMenuItem(text = { Text("Remove and sign out") }, onClick = { menu = false; confirm = true })
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = tokens.surface,
            title = { Text("Remove ${p.name}?", color = tokens.text) },
            text = { Text("The saved password or sign-in token is deleted from this phone.", color = tokens.textMuted) },
            confirmButton = { TextButton(onClick = { confirm = false; viewModel.removeProfile(p.id) }) { Text("Remove", color = tokens.danger) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun AddServerDialog(f: AddForm, viewModel: NetworkViewModel, onPlay: (PlayQueue) -> Unit) {
    val tokens = chaosTokens()
    val isJellyfin = f.type == ServerType.JELLYFIN
    val isUrl = f.type == ServerType.URL
    AlertDialog(
        onDismissRequest = { if (!f.testing) viewModel.cancelAdd() },
        containerColor = tokens.surface,
        title = { Text("Add ${f.type.label}", color = tokens.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val addressLabel = when (f.type) {
                    ServerType.JELLYFIN -> "Server address"
                    ServerType.NEXTCLOUD -> "Nextcloud address"
                    ServerType.WEBDAV -> "WebDAV folder URL"
                    ServerType.SMB -> "Server and share"
                    ServerType.FTP, ServerType.SFTP -> "Server"
                    ServerType.NFS -> "Server and export"
                    ServerType.DLNA -> "Server description URL"
                    else -> "Link"
                }
                val hint = when (f.type) {
                    ServerType.JELLYFIN -> "e.g. 192.168.1.10:8096 or jellyfin.example.com"
                    ServerType.NEXTCLOUD -> "e.g. cloud.example.com — use an app password if 2-factor sign-in is on (Settings › Security in Nextcloud)"
                    ServerType.WEBDAV -> "e.g. https://nas.local/dav/Movies"
                    ServerType.SMB -> "e.g. \\\\nas\\Movies, nas/Movies or 192.168.1.5 (leave the share out to pick one)"
                    ServerType.FTP -> "e.g. nas, 192.168.1.5:21 or ftp://nas/Videos"
                    ServerType.SFTP -> "e.g. nas, 192.168.1.5:22 or sftp://nas/home/me/Videos"
                    ServerType.NFS -> "e.g. nas:/volume1/video. The export needs this phone's IP and the 'insecure' option"
                    ServerType.DLNA -> "Usually found by Search on the Network tab. Or e.g. http://nas:8200/rootDesc.xml"
                    else -> "http(s), .m3u8, .mpd or rtsp link"
                }
                if (isUrl || f.type == ServerType.WEBDAV || f.type.isFileShare || f.type == ServerType.DLNA) {
                    OutlinedTextField(
                        value = f.name,
                        onValueChange = { v -> viewModel.updateForm { it.copy(name = v) } },
                        label = { Text("Name (optional)") },
                        singleLine = true,
                        enabled = !f.testing
                    )
                }
                OutlinedTextField(
                    value = f.address,
                    onValueChange = { v -> viewModel.updateForm { it.copy(address = v, error = null) } },
                    label = { Text(addressLabel) },
                    supportingText = { Text(hint) },
                    singleLine = true,
                    enabled = !f.testing,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.semantics { contentDescription = addressLabel }
                )
                if (!isUrl && f.type != ServerType.DLNA) {
                    OutlinedTextField(
                        value = f.username,
                        onValueChange = { v -> viewModel.updateForm { it.copy(username = v, error = null) } },
                        label = {
                            Text(
                                when (f.type) {
                                    ServerType.WEBDAV, ServerType.SMB -> "User name (blank for guest)"
                                    ServerType.FTP -> "User name (blank for anonymous)"
                                    ServerType.NFS -> "UID:GID (optional, e.g. 1000:1000)"
                                    else -> "User name"
                                }
                            )
                        },
                        supportingText = if (f.type == ServerType.SMB) ({ Text("DOMAIN\\user also works") }) else null,
                        singleLine = true,
                        enabled = !f.testing,
                        modifier = Modifier.semantics { contentDescription = "User name" }
                    )
                    if (f.type != ServerType.NFS) OutlinedTextField(
                        value = f.password,
                        onValueChange = { v -> viewModel.updateForm { it.copy(password = v, error = null) } },
                        label = { Text(if (f.type == ServerType.NEXTCLOUD) "Password or app password" else "Password") },
                        singleLine = true,
                        enabled = !f.testing,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.semantics { contentDescription = "Password" }
                    )
                    if (!f.type.isFileShare) Row(
                        Modifier.clickable(enabled = !f.testing) { viewModel.updateForm { it.copy(trustAll = !it.trustAll) } },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = f.trustAll, onCheckedChange = { v -> viewModel.updateForm { it.copy(trustAll = v) } }, enabled = !f.testing)
                        Text("Trust this server's certificate (self-signed)", style = ChaosType.Caption, color = tokens.textMuted)
                    }
                }
                f.quickConnectCode?.let { code ->
                    Spacer(Modifier.height(8.dp))
                    Text("Quick Connect code", style = ChaosType.Caption, color = tokens.textMuted)
                    Text(code, style = ChaosType.Title, color = tokens.accent)
                    Text("Enter it in Jellyfin on another device: Profile › Quick Connect. Waiting…", style = ChaosType.Caption, color = tokens.textMuted)
                }
                if (f.testing) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if (f.quickConnectCode != null) "Waiting for approval…" else "Testing connection…", style = ChaosType.Caption, color = tokens.textMuted)
                }
                f.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = ChaosType.Body, color = tokens.danger)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { viewModel.submitAdd(onPlay) },
                enabled = !f.testing && f.address.isNotBlank()
            ) { Text(if (isUrl) "Save and play" else if (isJellyfin) "Sign in" else "Connect", color = tokens.accent) }
        },
        dismissButton = {
            Row {
                if (isJellyfin && f.quickConnectCode == null) {
                    TextButton(onClick = { viewModel.startQuickConnect() }, enabled = !f.testing && f.address.isNotBlank()) { Text("Quick Connect") }
                }
                TextButton(onClick = { viewModel.cancelAdd() }) { Text("Cancel") }
            }
        }
    )
}

// ---------------------------------------------------------------- Jellyfin

@Composable
private fun rememberImageLoader(): ImageLoader {
    val context = LocalContext.current
    return remember { ImageLoader.Builder(context).okHttpClient(NetHttp.client).crossfade(true).build() }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun JellyfinBrowser(viewModel: NetworkViewModel, dest: NetDest, onPlay: (PlayQueue) -> Unit) {
    val tokens = chaosTokens()
    val browse by viewModel.browse.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val profileId = when (dest) {
        is NetDest.JellyfinHome -> dest.profileId
        is NetDest.JellyfinFolder -> dest.profileId
        is NetDest.JellyfinSearch -> dest.profileId
        else -> ""
    }
    val profile = profiles.firstOrNull { it.id == profileId }
    val title = when (dest) {
        is NetDest.JellyfinFolder -> dest.title
        is NetDest.JellyfinSearch -> "“${dest.query}”"
        else -> profile?.name ?: "Jellyfin"
    }
    val loader = rememberImageLoader()
    var detail by remember { mutableStateOf<Pair<RemoteEntry, List<RemoteEntry>>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val open: (RemoteEntry, List<RemoteEntry>) -> Unit = { entry, siblings ->
        if (entry.isFolder) viewModel.open(entry, siblings) else detail = entry to siblings
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        BrowserTopBar(
            title = title,
            subtitle = "Jellyfin",
            onBack = { viewModel.back() },
            onRefresh = { viewModel.reload() },
            extra = {
                IconButton(onClick = { searching = !searching }) { Icon(Icons.Filled.Search, contentDescription = "Search", tint = tokens.text) }
            }
        )
        if (searching) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search movies and shows") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search(query); searching = false }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ChaosSpacing.L.dp)
            )
        }
        BrowseStatus(browse, onRetry = { viewModel.reload() })

        val home = browse.home
        if (home != null) {
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                if (home.resume.isNotEmpty()) item { PosterRow("Continue watching", home.resume, loader, wide = true, onOpen = open) }
                if (home.nextUp.isNotEmpty()) item { PosterRow("Next up", home.nextUp, loader, wide = true, onOpen = open) }
                item { SectionLabel("Libraries", Modifier.padding(horizontal = ChaosSpacing.L.dp)) }
                items(home.libraries.chunked(2)) { pair ->
                    Row(Modifier.padding(horizontal = ChaosSpacing.L.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(ChaosSpacing.S.dp)) {
                        pair.forEach { lib -> Box(Modifier.weight(1f)) { PosterCard(lib, loader, wide = true) { open(lib, home.libraries) } } }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                if (home.latest.isNotEmpty()) item { PosterRow("Recently added", home.latest, loader, wide = false, onOpen = open) }
            }
        } else if (!browse.loading && browse.error == null) {
            if (browse.entries.isEmpty()) {
                Text("Nothing here.", style = ChaosType.Body, color = tokens.textMuted, modifier = Modifier.padding(ChaosSpacing.L.dp))
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(ChaosSpacing.L.dp),
                horizontalArrangement = Arrangement.spacedBy(ChaosSpacing.S.dp),
                verticalArrangement = Arrangement.spacedBy(ChaosSpacing.S.dp)
            ) {
                val playable = browse.entries.filter { it.isPlayable }
                if (playable.size > 1) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(ChaosSpacing.S.dp)) {
                            Button(onClick = { onPlay(viewModel.queueFor(playable.first(), playable, false)) }) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Play all ${playable.size}")
                            }
                        }
                    }
                }
                items(browse.entries, key = { it.id }) { entry ->
                    PosterCard(entry, loader, wide = entry.kind == "Episode" || entry.kind == "Video") { open(entry, browse.entries) }
                }
            }
        }
    }

    detail?.let { (entry, siblings) ->
        ModalBottomSheet(onDismissRequest = { detail = null }, containerColor = tokens.surface) {
            Column(
                Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = ChaosSpacing.L.dp)
                    .padding(bottom = ChaosSpacing.L.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(entry.name, style = ChaosType.Title, color = tokens.text)
                Text(
                    listOfNotNull(
                        entry.subtitle.takeIf { it.isNotBlank() },
                        entry.durationMs.takeIf { it > 0 }?.let { TimeFormat.format(it) },
                        if (entry.played) "Watched" else null
                    ).joinToString(" · "),
                    style = ChaosType.Caption,
                    color = tokens.textMuted
                )
                if (entry.overview.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(entry.overview, style = ChaosType.Body, color = tokens.text, maxLines = 8, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(ChaosSpacing.M.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(ChaosSpacing.S.dp)) {
                    if (entry.resumeMs > 5_000) {
                        Button(onClick = { detail = null; onPlay(viewModel.queueFor(entry, siblings, startOver = false)) }) {
                            Text("Resume from ${TimeFormat.format(entry.resumeMs)}")
                        }
                        OutlinedButton(onClick = { detail = null; onPlay(viewModel.queueFor(entry, siblings, startOver = true)) }) {
                            Text("Start over")
                        }
                    } else {
                        Button(onClick = { detail = null; onPlay(viewModel.queueFor(entry, siblings, startOver = true)) }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Play")
                        }
                    }
                }
                if (entry.subtitleUris.isNotEmpty()) {
                    Text(
                        "${entry.subtitleUris.size} subtitle file(s) on the server · choose them from the CC menu",
                        style = ChaosType.Caption,
                        color = tokens.textMuted,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PosterRow(
    title: String,
    entries: List<RemoteEntry>,
    loader: ImageLoader,
    wide: Boolean,
    onOpen: (RemoteEntry, List<RemoteEntry>) -> Unit
) {
    Column {
        SectionLabel(title, Modifier.padding(horizontal = ChaosSpacing.L.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = ChaosSpacing.L.dp),
            horizontalArrangement = Arrangement.spacedBy(ChaosSpacing.S.dp)
        ) {
            items(entries, key = { it.id }) { e ->
                Box(Modifier.width(if (wide) 220.dp else 120.dp)) { PosterCard(e, loader, wide) { onOpen(e, entries) } }
            }
        }
    }
}

@Composable
private fun PosterCard(entry: RemoteEntry, loader: ImageLoader, wide: Boolean, onClick: () -> Unit) {
    val tokens = chaosTokens()
    Column(
        Modifier
            .clip(RoundedCornerShape(ChaosShape.CARD.dp))
            .background(tokens.surface)
            .clickable(onClick = onClick)
            .semantics { contentDescription = entry.name }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (wide) 16f / 9f else 2f / 3f)
                .background(tokens.surface2),
            contentAlignment = Alignment.Center
        ) {
            Icon(if (entry.isFolder) Icons.Filled.Folder else Icons.Filled.Movie, contentDescription = null, tint = tokens.textMuted)
            if (entry.imageUrl != null) {
                AsyncImage(
                    model = entry.imageUrl,
                    imageLoader = loader,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (entry.resumeMs > 0 && entry.durationMs > 0) {
                LinearProgressIndicator(
                    progress = { (entry.resumeMs.toFloat() / entry.durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp),
                    color = tokens.accent,
                    trackColor = Color(0x66000000)
                )
            }
        }
        Column(Modifier.padding(ChaosSpacing.S.dp)) {
            Text(entry.name, style = ChaosType.Body, color = tokens.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (entry.subtitle.isNotBlank()) Text(entry.subtitle, style = ChaosType.Caption, color = tokens.textMuted, maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------- WebDAV / Nextcloud

@Composable
private fun DavBrowser(viewModel: NetworkViewModel, dest: NetDest.Dav, onPlay: (PlayQueue) -> Unit) {
    val tokens = chaosTokens()
    val browse by viewModel.browse.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val profile = profiles.firstOrNull { it.id == dest.profileId }
    val crumbs = if (profile != null) viewModel.breadcrumbs(dest, profile.baseUrl) else emptyList()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        BrowserTopBar(
            title = crumbs.lastOrNull()?.first?.takeIf { crumbs.size > 1 } ?: (profile?.name ?: "Files"),
            subtitle = profile?.type?.label ?: "WebDAV",
            onBack = { viewModel.back() },
            onRefresh = { viewModel.reload() }
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = ChaosSpacing.L.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            crumbs.forEachIndexed { i, (name, _) ->
                if (i > 0) Text(" › ", color = tokens.textMuted, style = ChaosType.Caption)
                Text(
                    name,
                    style = ChaosType.Caption,
                    color = if (i == crumbs.lastIndex) tokens.text else tokens.accent
                )
            }
            Spacer(Modifier.width(ChaosSpacing.M.dp))
            FilterChip(selected = browse.showAllFiles, onClick = { viewModel.toggleShowAllFiles() }, label = { Text("Show all files") })
        }
        BrowseStatus(browse, onRetry = { viewModel.reload() })
        if (!browse.loading && browse.error == null && browse.entries.isEmpty()) {
            Text("No folders or videos here.", style = ChaosType.Body, color = tokens.textMuted, modifier = Modifier.padding(ChaosSpacing.L.dp))
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            items(browse.entries, key = { it.id }) { e ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.open(e, browse.entries)?.let(onPlay) }
                        .padding(horizontal = ChaosSpacing.L.dp, vertical = ChaosSpacing.S.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        when (e.kind) {
                            "folder" -> Icons.Filled.Folder
                            "video" -> Icons.Filled.Movie
                            "audio" -> Icons.Filled.AudioFile
                            "subtitle" -> Icons.Filled.Subtitles
                            else -> Icons.Filled.Description
                        },
                        contentDescription = null,
                        tint = if (e.kind == "folder" || e.kind == "video") tokens.accent else tokens.textMuted
                    )
                    Spacer(Modifier.width(ChaosSpacing.M.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.name, style = ChaosType.Body, color = tokens.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val detail = listOfNotNull(
                            e.sizeBytes.takeIf { it > 0 }?.let { VideoItem.formatSize(it) },
                            if (e.subtitleUris.isNotEmpty()) "${e.subtitleUris.size} subtitle file(s)" else null
                        ).joinToString(" · ")
                        if (detail.isNotBlank()) Text(detail, style = ChaosType.Caption, color = tokens.textMuted)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- SMB / FTP / SFTP / NFS

@Composable
private fun ShareBrowser(viewModel: NetworkViewModel, dest: NetDest.Share, onPlay: (PlayQueue) -> Unit) {
    val tokens = chaosTokens()
    val browse by viewModel.browse.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val profile = profiles.firstOrNull { it.id == dest.profileId }
    val crumbs = com.chaoswares.chaosplay.network.RemotePaths.crumbs(dest.path, profile?.name ?: "Home")
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        BrowserTopBar(
            title = crumbs.last().first,
            subtitle = profile?.type?.label ?: "Share",
            onBack = { viewModel.back() },
            onRefresh = { viewModel.reload() }
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = ChaosSpacing.L.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            crumbs.forEachIndexed { i, (name, _) ->
                if (i > 0) Text(" › ", color = tokens.textMuted, style = ChaosType.Caption)
                Text(name, style = ChaosType.Caption, color = if (i == crumbs.lastIndex) tokens.text else tokens.accent)
            }
            Spacer(Modifier.width(ChaosSpacing.M.dp))
            FilterChip(selected = browse.showAllFiles, onClick = { viewModel.toggleShowAllFiles() }, label = { Text("Show all files") })
        }
        BrowseStatus(browse, onRetry = { viewModel.reload() })
        if (!browse.loading && browse.error == null && browse.entries.isEmpty()) {
            Text("No folders or videos here.", style = ChaosType.Body, color = tokens.textMuted, modifier = Modifier.padding(ChaosSpacing.L.dp))
        }
        FileRows(browse.entries) { e -> viewModel.open(e, browse.entries)?.let(onPlay) }
    }
}

@Composable
private fun DlnaBrowser(viewModel: NetworkViewModel, dest: NetDest.Dlna, onPlay: (PlayQueue) -> Unit) {
    val tokens = chaosTokens()
    val browse by viewModel.browse.collectAsState()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        BrowserTopBar(title = dest.title, subtitle = "DLNA", onBack = { viewModel.back() }, onRefresh = { viewModel.reload() })
        BrowseStatus(browse, onRetry = { viewModel.reload() })
        if (!browse.loading && browse.error == null && browse.entries.isEmpty()) {
            Text("This folder has no videos.", style = ChaosType.Body, color = tokens.textMuted, modifier = Modifier.padding(ChaosSpacing.L.dp))
        }
        FileRows(browse.entries) { e -> viewModel.open(e, browse.entries)?.let(onPlay) }
    }
}

@Composable
private fun FileRows(entries: List<RemoteEntry>, onClick: (RemoteEntry) -> Unit) {
    val tokens = chaosTokens()
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
        items(entries, key = { it.id }) { e ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onClick(e) }
                    .padding(horizontal = ChaosSpacing.L.dp, vertical = ChaosSpacing.S.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    when (e.kind) {
                        "folder" -> Icons.Filled.Folder
                        "video" -> Icons.Filled.Movie
                        "audio" -> Icons.Filled.AudioFile
                        "subtitle" -> Icons.Filled.Subtitles
                        else -> Icons.Filled.Description
                    },
                    contentDescription = null,
                    tint = if (e.kind == "folder" || e.kind == "video") tokens.accent else tokens.textMuted
                )
                Spacer(Modifier.width(ChaosSpacing.M.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.name, style = ChaosType.Body, color = tokens.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val detail = listOfNotNull(
                        e.sizeBytes.takeIf { it > 0 }?.let { VideoItem.formatSize(it) },
                        e.durationMs.takeIf { it > 0 }?.let { TimeFormat.format(it) },
                        if (e.subtitleUris.isNotEmpty()) "${e.subtitleUris.size} subtitle file(s)" else null,
                        e.subtitle.takeIf { it.isNotBlank() && e.isFolder }
                    ).joinToString(" · ")
                    if (detail.isNotBlank()) Text(detail, style = ChaosType.Caption, color = tokens.textMuted)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- shared bits

@Composable
private fun BrowserTopBar(title: String, subtitle: String, onBack: () -> Unit, onRefresh: () -> Unit, extra: @Composable () -> Unit = {}) {
    val tokens = chaosTokens()
    Row(Modifier.padding(horizontal = ChaosSpacing.XS.dp, vertical = ChaosSpacing.S.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = tokens.text) }
        Column(Modifier.weight(1f)) {
            Text(title, style = ChaosType.Heading, color = tokens.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = ChaosType.Caption, color = tokens.textMuted)
        }
        extra()
        IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = tokens.text) }
    }
}

@Composable
private fun BrowseStatus(browse: BrowseState, onRetry: () -> Unit) {
    val tokens = chaosTokens()
    if (browse.loading && browse.entries.isEmpty() && browse.home == null) {
        // First load of a folder: skeleton rows instead of a bare spinner
        Column(
            Modifier
                .padding(ChaosSpacing.L.dp)
                .semantics { contentDescription = "Loading" },
            verticalArrangement = Arrangement.spacedBy(ChaosSpacing.M.dp)
        ) {
            repeat(7) { i ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.chaoswares.chaosplay.ui.theme.SkeletonBlock(Modifier.size(32.dp), corner = 8.dp)
                    Spacer(Modifier.width(ChaosSpacing.M.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        com.chaoswares.chaosplay.ui.theme.SkeletonBlock(Modifier.fillMaxWidth(if (i % 2 == 0) 0.7f else 0.5f).height(14.dp))
                        com.chaoswares.chaosplay.ui.theme.SkeletonBlock(Modifier.fillMaxWidth(0.3f).height(10.dp))
                    }
                }
            }
        }
    } else if (browse.loading) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = ChaosSpacing.L.dp))
    }
    browse.error?.let {
        Card(
            colors = CardDefaults.cardColors(containerColor = tokens.surface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(ChaosSpacing.L.dp)
        ) {
            Column(Modifier.padding(ChaosSpacing.L.dp)) {
                Text("Could not load", style = ChaosType.Heading, color = tokens.danger)
                Text(it, style = ChaosType.Body, color = tokens.text)
                TextButton(onClick = onRetry) { Text("Try again", color = tokens.accent) }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = ChaosType.Heading,
        color = chaosTokens().text,
        modifier = modifier.padding(top = ChaosSpacing.M.dp, bottom = ChaosSpacing.XS.dp)
    )
}

@Composable
private fun EmptyCard(icon: ImageVector, title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    val tokens = chaosTokens()
    Card(
        colors = CardDefaults.cardColors(containerColor = tokens.surface),
        shape = RoundedCornerShape(ChaosShape.CARD.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(ChaosSpacing.L.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(tokens.surface2),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = tokens.textMuted, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(ChaosSpacing.M.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = ChaosType.Body, color = tokens.text)
                Text(body, style = ChaosType.Caption, color = tokens.textMuted)
            }
            if (action != null) TextButton(onClick = onAction) { Text(action, color = tokens.accent) }
        }
    }
}
