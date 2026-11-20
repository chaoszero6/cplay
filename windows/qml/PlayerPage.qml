import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts
import QtQuick.Window

// The whole window content: title bar, video (or idle logo screen), control bar.
// Windowed: the bars frame the video. Fullscreen: they float over it and hide.
Item {
    id: page
    property alias player: mpv
    property var queue: []          // locations
    property int queueIndex: -1
    property string currentLocation: ""
    property bool sizeAppliedForFile: false
    property bool startOverNext: false       // "Play from the beginning" (library menu)
    property alias home: home
    readonly property bool active: currentLocation.length > 0
    readonly property bool hasNext: queueIndex >= 0 && queueIndex < queue.length - 1
    readonly property bool hasPrevious: queueIndex > 0
    readonly property bool fullscreen: Window.window !== null && Window.window.visibility === Window.FullScreen
    readonly property bool menusOpen: mainMenu.opened || subtitleMenu.opened || audioMenu.opened || speedMenu.opened
    property bool controlsShown: true
    // Fullscreen and the mini player float the bars over the video and hide them
    readonly property bool overlayChrome: fullscreen || mini
    readonly property bool chromeShown: !overlayChrome || controlsShown || mpv.paused || menusOpen || controlBar.seeking
                                        || !active || mpv.errorString.length > 0

    // Phase 2 state ------------------------------------------------------
    property real abA: -1                     // A-B repeat points (ms), -1 = unset
    property real abB: -1
    property var bookmarkList: []             // [{positionMs, label, time}] of the current file
    property real sleepRemainingMs: 0         // sleep timer, 0 = off
    property bool sleepAtEnd: false           // "pause at the end of this video"
    property string repeatMode: app.setting("player/repeat", "off")     // off / one / all
    property bool autoQueueFolder: app.setting("player/autoQueueFolder", true)
    property bool previewEnabled: app.setting("player/seekPreview", true)
    property bool showControlBox: false
    property bool showPlaylist: false
    property bool locked: false
    property bool mini: false
    property bool pinnedBeforeMini: false
    // Picture / audio / subtitle adjustments (picture and subtitle look are remembered)
    property var picture: ({ brightness: app.setting("picture/brightness", 0), contrast: app.setting("picture/contrast", 0),
                             saturation: app.setting("picture/saturation", 0), hue: app.setting("picture/hue", 0),
                             gamma: app.setting("picture/gamma", 0) })
    property real zoom: 0
    property int rotation: 0
    property bool flipped: false
    property bool deinterlace: false
    property string aspect: "no"
    property real audioDelayMs: 0
    property bool levelling: app.setting("player/levelling", false)
    property real subDelayMs: 0
    property real subScale: app.setting("subtitles/scale", 1.0)
    property int subPos: app.setting("subtitles/pos", 100)
    property string subCodepage: app.setting("subtitles/codepage", "auto")
    readonly property string mediaTitle: active ? (mpv.title.length > 0 ? mpv.title : app.displayName(currentLocation)) : ""

    signal closeRequested()
    signal toggleFullscreen()
    signal openFileRequested()
    signal openLinkRequested()
    signal subtitleFileRequested()
    signal quitRequested()
    signal customSizeRequested()
    signal addFilesRequested()
    signal addFolderRequested()
    signal openFolderRequested()
    signal loadPlaylistRequested()
    signal savePlaylistRequested()

    // ---- playback --------------------------------------------------------
    function playQueue(locations, index) {
        saveProgress(false)
        queue = locations
        playAt(index)
    }

    function playAt(index) {
        if (index < 0 || index >= queue.length) return
        if (active) saveProgress(false)
        queueIndex = index
        currentLocation = queue[index]
        sizeAppliedForFile = false
        const start = startOverNext ? 0 : app.startPositionFor(currentLocation)
        startOverNext = false
        mpv.open(currentLocation, start)
        mpv.setVolume(app.volume)
        osd.show(start > 0 ? "Resume at " + app.formatClock(start) + "   (Home: start over)" : app.displayName(currentLocation))
        page.forceActiveFocus()
        poke()
    }

    function next() { if (hasNext) playAt(queueIndex + 1) }
    function previous() {
        // Past 5 s, "previous" restarts the current video
        if (mpv.position > 5000 || !hasPrevious) mpv.seekTo(0)
        else playAt(queueIndex - 1)
    }

    function saveProgress(finished) {
        if (!active) return
        app.saveProgress(currentLocation, mediaTitle, mpv.position, mpv.duration, finished)
    }

    function close() {
        saveProgress(mpv.ended)
        mpv.stop()
        app.keepAwake(false)
        currentLocation = ""
        queue = []
        queueIndex = -1
        closeRequested()
    }

    function poke() { controlsShown = true; hideTimer.restart() }

    function changeVolume(delta) {
        const v = Math.max(0, Math.min(150, Math.round(mpv.volume + delta)))
        mpv.setVolume(v)
        app.volume = v
        if (mpv.muted) mpv.setMuted(false)
        osd.show("Volume " + v + "%")
    }

    function setSpeed(s) { mpv.setSpeed(s); osd.show("Speed " + app.formatSpeed(s)) }

    function toggleOnTop() {
        app.alwaysOnTop = !app.alwaysOnTop
        osd.show(app.alwaysOnTop ? "Always on top: on" : "Always on top: off")
    }

    function cycleSubtitles() {
        const subs = mpv.subtitleTracks
        if (subs.length === 0) { osd.show("No subtitles"); return }
        const i = subs.findIndex(t => t.id === mpv.subtitleId)
        const nextId = i + 1 < subs.length ? subs[i + 1].id : 0
        mpv.setSubtitleTrack(nextId)
        osd.show(nextId === 0 ? "Subtitles off" : "Subtitles: " + subs[i + 1].label)
    }

    function setWindowScale(scale) {
        if (mpv.videoWidth <= 0) { osd.show("Open a video first"); return }
        windowCtl.fitVideo(scale, mpv.videoWidth, mpv.videoHeight, 2, titleBar.height + controlBar.height + 2)
        osd.show("Window " + Math.round(scale * 100) + "%")
    }

    // ---- queue and playlist --------------------------------------------
    /** Opening one video queues the rest of its folder (setting, default on). */
    function playLocations(locations) {
        if (locations.length === 1 && autoQueueFolder && !app.isUrl(locations[0])) {
            const q = tools.folderQueue(locations[0])
            playQueue(q.files, q.index)
        } else {
            playQueue(locations, 0)
        }
    }
    function addToQueue(locations) {
        if (locations.length === 0) return
        if (!active) { playQueue(locations, 0); return }
        queue = queue.concat(locations)
        osd.show(locations.length === 1 ? "Added to the playlist" : locations.length + " videos added to the playlist")
    }
    function removeFromQueue(i) {
        if (i < 0 || i >= queue.length) return
        if (i === queueIndex) {
            if (queue.length === 1) { close(); return }
            const q = queue.slice(); q.splice(i, 1)
            queue = q
            playAt(Math.min(i, q.length - 1))
            return
        }
        const q = queue.slice(); q.splice(i, 1)
        if (i < queueIndex) queueIndex--
        queue = q
    }
    function moveInQueue(from, to) {
        if (from < 0 || to < 0 || from >= queue.length || to >= queue.length || from === to) return
        const q = queue.slice()
        const item = q.splice(from, 1)[0]
        q.splice(to, 0, item)
        if (queueIndex === from) queueIndex = to
        else if (from < queueIndex && to >= queueIndex) queueIndex--
        else if (from > queueIndex && to <= queueIndex) queueIndex++
        queue = q
    }
    function clearQueue() {
        if (!active) { queue = []; return }
        queue = [currentLocation]
        queueIndex = 0
    }
    function shuffleQueue() {
        if (queue.length < 3) return
        const current = queue[queueIndex]
        const rest = queue.filter((_, i) => i !== queueIndex)
        for (let i = rest.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [rest[i], rest[j]] = [rest[j], rest[i]] }
        queue = [current].concat(rest)
        queueIndex = 0
        osd.show("Playlist shuffled")
    }
    function cycleRepeat() {
        repeatMode = repeatMode === "off" ? "all" : repeatMode === "all" ? "one" : "off"
        app.setSetting("player/repeat", repeatMode)
        osd.show(repeatMode === "off" ? "Repeat off" : repeatMode === "all" ? "Repeat the whole list" : "Repeat this video")
    }
    function onVideoEnded() {
        saveProgress(true)
        if (sleepAtEnd) { sleepAtEnd = false; osd.show("Sleep timer: stopped at the end"); return }
        if (repeatMode === "one") { mpv.seekTo(0); mpv.play(); return }
        if (hasNext) { next(); return }
        if (repeatMode === "all" && queue.length > 0) playAt(0)
    }

    // ---- A-B repeat, bookmarks, sleep timer -----------------------------
    function pressAb() {
        if (!active) return
        const r = tools.abPress(abA, abB, mpv.position)
        abA = r.a; abB = r.b
        mpv.setMpvProperty("ab-loop-a", abA >= 0 ? (abA / 1000).toFixed(3) : "no")
        mpv.setMpvProperty("ab-loop-b", abB >= 0 ? (abB / 1000).toFixed(3) : "no")
        osd.show(r.active ? "A-B repeat: " + app.formatClock(abA) + " to " + app.formatClock(abB)
                 : r.armed ? "A-B repeat: A set at " + app.formatClock(abA) + ", press again for B"
                 : "A-B repeat off")
    }
    function clearAb() {
        abA = -1; abB = -1
        mpv.setMpvProperty("ab-loop-a", "no")
        mpv.setMpvProperty("ab-loop-b", "no")
    }
    function refreshBookmarks() { bookmarkList = active ? tools.bookmarks(currentLocation) : [] }
    function addBookmark() {
        if (!active) return
        osd.show(tools.addBookmark(currentLocation, mpv.position, "")
                 ? "Bookmark added at " + app.formatClock(mpv.position) : "Already bookmarked here")
    }
    function jumpBookmark(forward) {
        if (!active) return
        const ms = forward ? tools.nextBookmark(currentLocation, mpv.position) : tools.previousBookmark(currentLocation, mpv.position)
        if (ms < 0) { osd.show(forward ? "No later bookmark" : "No earlier bookmark"); return }
        mpv.seekTo(ms)
        osd.show("Bookmark " + app.formatClock(ms))
    }
    function setSleep(minutes) {
        sleepAtEnd = false
        sleepRemainingMs = minutes * 60000
        osd.show(minutes > 0 ? "Sleep timer: pause in " + minutes + " min" : "Sleep timer off")
    }
    function setSleepAtEnd() {
        sleepRemainingMs = 0
        sleepAtEnd = true
        osd.show("Sleep timer: pause at the end of this video")
    }

    // ---- control box ------------------------------------------------------
    function setPicture(name, value) {
        const p = Object.assign({}, picture)
        p[name] = Math.max(-100, Math.min(100, value))
        picture = p
        mpv.setMpvProperty(name, p[name])
        app.setSetting("picture/" + name, p[name])
        osd.show(tools.pictureLabel(name.charAt(0).toUpperCase() + name.slice(1), p[name]))
    }
    function setZoom(z) { zoom = z; mpv.setMpvProperty("video-zoom", z.toFixed(2)); osd.show("Zoom " + tools.zoomPercent(z) + "%") }
    function rotate() { rotation = tools.nextRotation(rotation); mpv.setMpvProperty("video-rotate", rotation); osd.show("Rotate " + rotation + "°") }
    function setFlipped(on) { flipped = on; mpv.setMpvProperty("vf", on ? "hflip" : ""); osd.show(on ? "Mirrored" : "Mirror off") }
    function setDeinterlace(on) { deinterlace = on; mpv.setMpvProperty("deinterlace", on); osd.show(on ? "Deinterlace on" : "Deinterlace off") }
    function setAspect(value) {
        aspect = value
        mpv.setMpvProperty("video-aspect-override", value)
        const preset = tools.aspectPresets().find(p => p.value === value)
        osd.show("Aspect ratio: " + (preset ? preset.label : value))
    }
    function setAudioDelay(ms) {
        audioDelayMs = Math.max(-10000, Math.min(10000, ms))
        mpv.setMpvProperty("audio-delay", (audioDelayMs / 1000).toFixed(3))
        osd.show(tools.delayLabel("Audio", audioDelayMs))
    }
    function setLevelling(on) {
        levelling = on
        app.setSetting("player/levelling", on)
        mpv.setMpvProperty("af", on ? "lavfi=[dynaudnorm=f=250:g=15:p=0.9]" : "")
        osd.show(on ? "Volume levelling on" : "Volume levelling off")
    }
    function setSubDelay(ms) {
        subDelayMs = Math.max(-10000, Math.min(10000, ms))
        mpv.setMpvProperty("sub-delay", (subDelayMs / 1000).toFixed(3))
        osd.show(tools.delayLabel("Subtitle", subDelayMs))
    }
    function setSubScale(sc) {
        subScale = Math.max(0.3, Math.min(3.0, sc))
        mpv.setMpvProperty("sub-scale", subScale.toFixed(2))
        app.setSetting("subtitles/scale", subScale)
        osd.show("Subtitle size " + Math.round(subScale * 100) + "%")
    }
    function setSubPos(pos) {
        subPos = Math.max(0, Math.min(150, pos))
        mpv.setMpvProperty("sub-pos", subPos)
        app.setSetting("subtitles/pos", subPos)
    }
    function setSubCodepage(cp) {
        subCodepage = cp
        app.setSetting("subtitles/codepage", cp)
        mpv.setMpvProperty("sub-codepage", cp)
        // Reload the current text subtitle so the new encoding applies now
        if (mpv.subtitleId > 0) mpv.mpvCommand(["sub-reload", String(mpv.subtitleId)])
        osd.show("Subtitle encoding: " + cp)
    }
    /** Control box "reset" for one tab: 0 picture, 1 audio, 2 subtitles. */
    function resetAdjustments(tab) {
        if (tab === 0) {
            ["brightness", "contrast", "saturation", "hue", "gamma"].forEach(n => setPicture(n, 0))
            setZoom(0); rotation = 270; rotate(); if (flipped) setFlipped(false); if (deinterlace) setDeinterlace(false); setAspect("no")
        } else if (tab === 1) {
            setAudioDelay(0); if (levelling) setLevelling(false)
        } else {
            setSubDelay(0); setSubScale(1.0); setSubPos(100); setSubCodepage("auto")
        }
        osd.show("Reset")
    }
    /** Per-video settings go back to normal when another video starts. */
    function resetPerFile() {
        clearAb()
        audioDelayMs = 0; subDelayMs = 0; zoom = 0; rotation = 0; flipped = false; aspect = "no"
        mpv.setMpvProperty("video-zoom", "0")
        mpv.setMpvProperty("video-rotate", "0")
        mpv.setMpvProperty("video-aspect-override", "no")
        mpv.setMpvProperty("vf", "")
        refreshBookmarks()
    }
    /** Remembered settings, applied once when the player starts. */
    function applySavedSettings() {
        ["brightness", "contrast", "saturation", "hue", "gamma"].forEach(n => { if (picture[n] !== 0) mpv.setMpvProperty(n, picture[n]) })
        if (levelling) mpv.setMpvProperty("af", "lavfi=[dynaudnorm=f=250:g=15:p=0.9]")
        if (subScale !== 1.0) mpv.setMpvProperty("sub-scale", subScale.toFixed(2))
        if (subPos !== 100) mpv.setMpvProperty("sub-pos", subPos)
        if (subCodepage !== "auto") mpv.setMpvProperty("sub-codepage", subCodepage)
    }

    // ---- lock and mini player -------------------------------------------
    function setLocked(on) {
        if (on && !active) return
        locked = on
        if (on) { showControlBox = false; showPlaylist = false; mainMenu.close(); lockOverlay.hint() }
        page.forceActiveFocus()
        osd.show(on ? "Locked" : "Unlocked")
    }
    function setMini(on) {
        if (on === mini) return
        if (on) {
            pinnedBeforeMini = app.alwaysOnTop
            app.alwaysOnTop = true          // a mini player floats above everything
        } else if (!pinnedBeforeMini) {
            app.alwaysOnTop = false
        }
        mini = on
        windowCtl.setMini(on)
        osd.show(on ? "Mini player (Ctrl+M to go back)" : "Normal size")
    }

    function osdMessage(text) { osd.show(text) }

    function popupAbove(menu, button) {
        menu.parent = button
        menu.x = Math.min(0, button.width - menu.implicitWidth)
        menu.y = -menu.implicitHeight - 6
        menu.open()
    }
    function openSubtitleMenu(button) { popupAbove(subtitleMenu, button) }
    function openAudioMenu(button) { popupAbove(audioMenu, button) }
    function openSpeedMenu(button) { popupAbove(speedMenu, button) }
    function openMainMenuAt(x, y) {
        mainMenu.parent = page
        mainMenu.x = Math.min(x, page.width - mainMenu.implicitWidth)
        mainMenu.y = Math.min(y, page.height - mainMenu.implicitHeight)
        mainMenu.open()
    }
    function openMainMenu(anchor, below) {
        mainMenu.parent = anchor
        mainMenu.x = below ? 0 : Math.min(0, anchor.width - mainMenu.implicitWidth)
        mainMenu.y = below ? anchor.height + 2 : -mainMenu.implicitHeight - 6
        mainMenu.open()
    }

    focus: true

    // ---- title bar ---------------------------------------------------------
    TitleBar {
        id: titleBar
        z: 5
        anchors { left: parent.left; right: parent.right }
        y: page.chromeShown ? 0 : -height
        Behavior on y { NumberAnimation { duration: Theme.fadeMs; easing.type: Easing.OutCubic } }
        title: page.active ? page.mediaTitle : "CPlay"
        fullscreen: page.fullscreen
        onMenuRequested: (anchor) => page.openMainMenu(anchor, true)
        onToggleOnTop: page.toggleOnTop()
        showHome: page.active
        onHomeRequested: page.close()
        onCloseRequested: page.quitRequested()
    }

    // ---- video ---------------------------------------------------------------
    Item {
        id: videoArea
        anchors {
            left: parent.left; right: parent.right
            top: page.overlayChrome ? parent.top : titleBar.bottom
            bottom: page.overlayChrome || !page.active ? parent.bottom : controlBar.top
        }
        // No clip here: clipping the mpv framebuffer item leaves the picture black

        Rectangle { anchors.fill: parent; color: "black" }

        MpvPlayer {
            id: mpv
            anchors.fill: parent
            Component.onCompleted: {
                if (!app.hardwareDecoding) setMpvProperty("hwdec", "no")
                page.applySavedSettings()
            }
            onFileLoaded: { page.resetPerFile(); page.poke() }
            onEndReached: page.onVideoEnded()
            onPausedChanged: app.keepAwake(!paused && page.active)
            // "Window size on play": once per file, as soon as its size is known
            onVideoSizeChanged: {
                if (page.sizeAppliedForFile || videoWidth <= 0 || videoHeight <= 0) return
                page.sizeAppliedForFile = true
                windowCtl.applySizeOnPlay(videoWidth, videoHeight, 2, titleBar.height + controlBar.height + 2)
            }
            onScreenshotSaved: (dir) => osd.show("Screenshot saved to " + dir)
        }

        // Mouse on the video: click = pause, double-click = fullscreen, wheel = volume,
        // right-click = menu, drag = move the window (like classic players)
        MouseArea {
            id: videoMouse
            anchors.fill: parent
            hoverEnabled: true
            acceptedButtons: Qt.LeftButton | Qt.RightButton
            cursorShape: page.chromeShown || !page.overlayChrome ? Qt.ArrowCursor : Qt.BlankCursor
            property point pressPoint
            property bool movingWindow: false
            onPressed: (m) => { pressPoint = Qt.point(m.x, m.y); movingWindow = false }
            onPositionChanged: (m) => {
                page.poke()
                if (!(m.buttons & Qt.LeftButton) || page.fullscreen) return
                if (!movingWindow && Math.abs(m.x - pressPoint.x) + Math.abs(m.y - pressPoint.y) > 6) {
                    movingWindow = true
                    clickTimer.stop()
                    windowCtl.startDrag()
                }
                if (movingWindow) windowCtl.dragMove()
            }
            onReleased: if (movingWindow) windowCtl.endDrag()
            onClicked: (m) => {
                if (m.button === Qt.RightButton) { mainMenu.popup(); return }
                if (!movingWindow && page.active) clickTimer.restart()
            }
            onDoubleClicked: (m) => { if (m.button === Qt.LeftButton) { clickTimer.stop(); page.toggleFullscreen() } }
            onWheel: (w) => { if (page.active) page.changeVolume(w.angleDelta.y > 0 ? 5 : -5); page.poke() }
        }

        // Nothing playing: the library, network sources and settings
        HomeScreen {
            id: home
            anchors.fill: parent
            host: page
            visible: !page.active
            onOpenFiles: page.openFileRequested()
            onOpenLink: page.openLinkRequested()
            onCustomSizeRequested: page.customSizeRequested()
        }

        // Paused: small play badge; loading/buffering: spinner after a short delay
        Rectangle {
            anchors.centerIn: parent
            width: 72; height: 72; radius: 36
            color: Theme.scrim
            visible: page.active && mpv.paused && mpv.firstFrameShown && mpv.errorString.length === 0
            Icon { anchors.centerIn: parent; name: mpv.ended ? "replay" : "play_arrow"; size: 44; color: "white" }
        }
        BusyIndicator {
            id: spinner
            anchors.centerIn: parent
            running: false
            Timer {
                interval: Theme.bufferingDelayMs
                running: (mpv.buffering || (page.active && !mpv.firstFrameShown)) && mpv.errorString.length === 0
                onTriggered: spinner.running = true
                onRunningChanged: if (!running) spinner.running = false
            }
        }

        // Error card
        Rectangle {
            anchors.centerIn: parent
            visible: page.active && mpv.errorString.length > 0
            width: Math.min(parent.width - 48, 460); height: errorCol.implicitHeight + 40
            radius: 6
            color: Theme.menuBg
            border.color: Theme.chromeBorder
            ColumnLayout {
                id: errorCol
                anchors.fill: parent; anchors.margins: 20
                spacing: 12
                Icon { name: "error_outline"; color: Theme.danger; size: 32 }
                Text { Layout.fillWidth: true; wrapMode: Text.Wrap; color: Theme.text; font.pixelSize: 14; font.family: Theme.fontFamily; text: mpv.errorString }
                Text { Layout.fillWidth: true; elide: Text.ElideMiddle; color: Theme.textMuted; font.pixelSize: 12; text: page.currentLocation }
                RowLayout {
                    Button { text: "Close"; onClicked: page.close() }
                    Button { text: "Try again"; onClicked: page.playAt(page.queueIndex) }
                    Button { text: "Next"; visible: page.hasNext; onClicked: page.next() }
                }
            }
        }

        Osd {
            id: osd
            anchors { left: parent.left; top: parent.top; leftMargin: 16; topMargin: page.overlayChrome && page.chromeShown ? titleBar.height + 12 : 12 }
            z: 4
        }

        PlaylistPanel {
            id: playlistPanel
            host: page
            visible: page.showPlaylist && !page.locked
            z: 6
            anchors { right: parent.right; top: parent.top; bottom: parent.bottom
                      topMargin: page.overlayChrome && page.chromeShown ? titleBar.height : 0
                      bottomMargin: page.overlayChrome && page.chromeShown ? controlBar.height : 0 }
            width: Math.min(320, parent.width * 0.6)
            onAddFilesRequested: page.addFilesRequested()
            onAddFolderRequested: page.addFolderRequested()
            onLoadRequested: page.loadPlaylistRequested()
            onSaveRequested: page.savePlaylistRequested()
        }

        ControlBox {
            id: controlBox
            host: page
            visible: page.showControlBox && !page.locked
            z: 7
            x: 16
            y: page.overlayChrome && page.chromeShown ? titleBar.height + 64 : 64
            maxHeight: parent.height - y - 8
        }
    }

    // ---- lock (covers everything, bars included) ---------------------------
    LockOverlay {
        id: lockOverlay
        anchors.fill: parent
        visible: page.locked
        z: 50
        onUnlockRequested: page.setLocked(false)
    }
    // Hold Esc for 3 s to unlock
    Keys.onPressed: (e) => {
        if (page.locked) {
            if (e.key === Qt.Key_Escape && !e.isAutoRepeat) lockOverlay.holding = true
            else lockOverlay.hint()
            e.accepted = true
        }
    }
    Keys.onReleased: (e) => {
        if (page.locked && e.key === Qt.Key_Escape && !e.isAutoRepeat) { lockOverlay.holding = false; e.accepted = true }
    }

    // ---- control bar ----------------------------------------------------------
    ControlBar {
        id: controlBar
        z: 5
        host: page
        visible: page.active
        anchors { left: parent.left; right: parent.right }
        y: page.chromeShown ? page.height - height : page.height
        Behavior on y { NumberAnimation { duration: Theme.fadeMs; easing.type: Easing.OutCubic } }
        onMenuRequested: (anchor) => page.openMainMenu(anchor, false)
        HoverHandler { onHoveredChanged: if (hovered) page.poke() }
    }

    Timer { interval: 5000; repeat: true; running: !mpv.paused && page.active; onTriggered: page.saveProgress(false) }
    Timer { id: hideTimer; interval: Theme.autoHideMs; onTriggered: page.controlsShown = false }
    // Sleep timer: counts down in real time while something is playing
    Timer {
        interval: 1000; repeat: true
        running: page.sleepRemainingMs > 0 && page.active
        onTriggered: {
            page.sleepRemainingMs = Math.max(0, page.sleepRemainingMs - 1000)
            if (page.sleepRemainingMs === 0) { mpv.pause(); osd.show("Sleep timer: paused") }
        }
    }
    // Taskbar button: progress and thumbnail buttons (Windows)
    Timer {
        interval: 1000; repeat: true; running: true; triggeredOnStart: true
        onTriggered: taskbar.update(page.active, page.active && !mpv.paused,
                                    mpv.duration > 0 ? mpv.position / mpv.duration : 0,
                                    page.active, page.hasNext)
    }
    Connections {
        target: taskbar
        function onPlayPauseClicked() { if (page.active) mpv.togglePause() }
        function onPreviousClicked() { if (page.active) page.previous() }
        function onNextClicked() { page.next() }
    }
    Connections {
        target: tools
        function onBookmarksChanged() { page.refreshBookmarks() }
    }
    Timer { id: clickTimer; interval: 230; onTriggered: { mpv.togglePause(); page.poke() } }

    // ---- menus ------------------------------------------------------------------
    ChaosMenu {
        id: subtitleMenu
        ChaosMenuItem { text: "Off"; checkable: true; checked: mpv.subtitleId === 0; onTriggered: mpv.setSubtitleTrack(0) }
        Repeater {
            model: mpv.subtitleTracks
            ChaosMenuItem { required property var modelData; text: modelData.label; checkable: true; checked: modelData.id === mpv.subtitleId; onTriggered: mpv.setSubtitleTrack(modelData.id) }
        }
        MenuSeparator {}
        ChaosMenuItem { text: "Load subtitle file…"; onTriggered: page.subtitleFileRequested() }
    }
    ChaosMenu {
        id: audioMenu
        Repeater {
            model: mpv.audioTracks
            ChaosMenuItem { required property var modelData; text: modelData.label; checkable: true; checked: modelData.id === mpv.audioId; onTriggered: mpv.setAudioTrack(modelData.id) }
        }
        ChaosMenuItem { text: "Off"; checkable: true; checked: mpv.audioId === 0; onTriggered: mpv.setAudioTrack(0) }
    }
    ChaosMenu {
        id: speedMenu
        implicitWidth: 150
        Repeater {
            model: app.speedPresets()
            ChaosMenuItem { required property var modelData; implicitWidth: 150; text: app.formatSpeed(modelData); checkable: true; checked: Math.abs(modelData - mpv.speed) < 0.001; onTriggered: page.setSpeed(modelData) }
        }
    }

    // Main menu: logo button, ≡ button and right-click
    ChaosMenu {
        id: mainMenu
        ChaosMenuItem { text: "Open file…"; shortcut: "Ctrl+O"; onTriggered: page.openFileRequested() }
        ChaosMenuItem { text: "Open link…"; shortcut: "Ctrl+U"; onTriggered: page.openLinkRequested() }
        ChaosMenuItem { text: "Open folder…"; shortcut: "Ctrl+Shift+O"; onTriggered: page.openFolderRequested() }
        ChaosMenuItem { text: "Playlist"; shortcut: "Ctrl+P"; checkable: true; checked: page.showPlaylist; onTriggered: page.showPlaylist = !page.showPlaylist }
        ChaosMenu {
            title: "Recent"
            enabled: app.recent.length > 0
            Repeater {
                model: app.recent.slice(0, 10)
                ChaosMenuItem { required property var modelData; text: modelData.title; onTriggered: page.playQueue([modelData.location], 0) }
            }
        }
        MenuSeparator {}
        ChaosMenuItem { text: mpv.paused ? "Play" : "Pause"; shortcut: "Space"; enabled: page.active; onTriggered: mpv.togglePause() }
        ChaosMenuItem { text: "Stop"; enabled: page.active; onTriggered: page.close() }
        ChaosMenu {
            title: "Playback"
            ChaosMenuItem { text: page.abA < 0 ? "A-B repeat: set A" : page.abB < 0 ? "A-B repeat: set B" : "A-B repeat: off"; shortcut: "L"; enabled: page.active; onTriggered: page.pressAb() }
            MenuSeparator {}
            ChaosMenuItem { text: "Repeat off"; checkable: true; checked: page.repeatMode === "off"; onTriggered: { page.repeatMode = "all"; page.cycleRepeat() } }
            ChaosMenuItem { text: "Repeat the whole list"; checkable: true; checked: page.repeatMode === "all"; onTriggered: { page.repeatMode = "off"; page.cycleRepeat() } }
            ChaosMenuItem { text: "Repeat this video"; checkable: true; checked: page.repeatMode === "one"; onTriggered: { page.repeatMode = "all"; page.cycleRepeat() } }
            MenuSeparator {}
            ChaosMenuItem {
                text: "Queue the rest of the folder"
                checkable: true; checked: page.autoQueueFolder
                onTriggered: { page.autoQueueFolder = !page.autoQueueFolder; app.setSetting("player/autoQueueFolder", page.autoQueueFolder) }
            }
            ChaosMenu {
                title: "Sleep timer"
                Repeater {
                    model: tools.sleepPresets()
                    ChaosMenuItem { required property var modelData; text: modelData + " minutes"; onTriggered: page.setSleep(modelData) }
                }
                ChaosMenuItem { text: "At the end of this video"; checkable: true; checked: page.sleepAtEnd; onTriggered: page.setSleepAtEnd() }
                ChaosMenuItem { text: "Off"; enabled: page.sleepRemainingMs > 0 || page.sleepAtEnd; onTriggered: page.setSleep(0) }
            }
        }
        ChaosMenu {
            title: "Bookmarks"
            enabled: page.active
            ChaosMenuItem { text: "Add bookmark here"; shortcut: "B"; onTriggered: page.addBookmark() }
            ChaosMenuItem { text: "Previous bookmark"; shortcut: "Ctrl+Left"; enabled: page.bookmarkList.length > 0; onTriggered: page.jumpBookmark(false) }
            ChaosMenuItem { text: "Next bookmark"; shortcut: "Ctrl+Right"; enabled: page.bookmarkList.length > 0; onTriggered: page.jumpBookmark(true) }
            MenuSeparator { visible: page.bookmarkList.length > 0 }
            Repeater {
                model: page.bookmarkList
                ChaosMenuItem { required property var modelData; text: modelData.label; shortcut: modelData.time; onTriggered: mpv.seekTo(modelData.positionMs) }
            }
            MenuSeparator { visible: page.bookmarkList.length > 0 }
            ChaosMenuItem { text: "Delete all bookmarks of this video"; enabled: page.bookmarkList.length > 0; onTriggered: tools.clearBookmarks(page.currentLocation) }
        }
        ChaosMenu {
            title: "Speed"
            Repeater {
                model: app.speedPresets()
                ChaosMenuItem { required property var modelData; text: app.formatSpeed(modelData); checkable: true; checked: Math.abs(modelData - mpv.speed) < 0.001; onTriggered: page.setSpeed(modelData) }
            }
        }
        ChaosMenu {
            title: "Subtitles"
            ChaosMenuItem { text: "Off"; checkable: true; checked: mpv.subtitleId === 0; onTriggered: mpv.setSubtitleTrack(0) }
            Repeater {
                model: mpv.subtitleTracks
                ChaosMenuItem { required property var modelData; text: modelData.label; checkable: true; checked: modelData.id === mpv.subtitleId; onTriggered: mpv.setSubtitleTrack(modelData.id) }
            }
            ChaosMenuItem { text: "Load subtitle file…"; onTriggered: page.subtitleFileRequested() }
            MenuSeparator {}
            ChaosMenuItem { text: "Subtitles later (+0.1 s)"; shortcut: "Alt+="; onTriggered: page.setSubDelay(tools.stepDelay(page.subDelayMs, true)) }
            ChaosMenuItem { text: "Subtitles earlier (-0.1 s)"; shortcut: "Alt+-"; onTriggered: page.setSubDelay(tools.stepDelay(page.subDelayMs, false)) }
            ChaosMenuItem { text: "Bigger subtitles"; shortcut: "Alt+Up"; onTriggered: page.setSubScale(tools.stepSubScale(page.subScale, true)) }
            ChaosMenuItem { text: "Smaller subtitles"; shortcut: "Alt+Down"; onTriggered: page.setSubScale(tools.stepSubScale(page.subScale, false)) }
            ChaosMenuItem { text: "Subtitle settings…"; shortcut: "Ctrl+G"; onTriggered: { controlBox.tab = 2; page.showControlBox = true } }
        }
        ChaosMenu {
            title: "Audio"
            Repeater {
                model: mpv.audioTracks
                ChaosMenuItem { required property var modelData; text: modelData.label; checkable: true; checked: modelData.id === mpv.audioId; onTriggered: mpv.setAudioTrack(modelData.id) }
            }
            MenuSeparator {}
            ChaosMenuItem { text: "Sound later (+0.1 s)"; shortcut: "Ctrl+="; onTriggered: page.setAudioDelay(tools.stepDelay(page.audioDelayMs, true)) }
            ChaosMenuItem { text: "Sound earlier (-0.1 s)"; shortcut: "Ctrl+-"; onTriggered: page.setAudioDelay(tools.stepDelay(page.audioDelayMs, false)) }
            ChaosMenuItem { text: "Reset sound timing"; enabled: page.audioDelayMs !== 0; onTriggered: page.setAudioDelay(0) }
            ChaosMenuItem { text: "Volume levelling"; checkable: true; checked: page.levelling; onTriggered: page.setLevelling(!page.levelling) }
        }
        ChaosMenu {
            title: "Video"
            ChaosMenuItem { text: "Control box…"; shortcut: "Ctrl+G"; onTriggered: { controlBox.tab = 0; page.showControlBox = true } }
            ChaosMenu {
                title: "Aspect ratio"
                Repeater {
                    model: tools.aspectPresets()
                    ChaosMenuItem { required property var modelData; text: modelData.label; checkable: true; checked: page.aspect === modelData.value; onTriggered: page.setAspect(modelData.value) }
                }
            }
            ChaosMenuItem { text: "Zoom in"; shortcut: "Ctrl+Up"; onTriggered: page.setZoom(tools.stepZoom(page.zoom, true)) }
            ChaosMenuItem { text: "Zoom out"; shortcut: "Ctrl+Down"; onTriggered: page.setZoom(tools.stepZoom(page.zoom, false)) }
            ChaosMenuItem { text: "Rotate 90°"; shortcut: "Ctrl+R"; onTriggered: page.rotate() }
            ChaosMenuItem { text: "Mirror"; checkable: true; checked: page.flipped; onTriggered: page.setFlipped(!page.flipped) }
            ChaosMenuItem { text: "Deinterlace"; shortcut: "D"; checkable: true; checked: page.deinterlace; onTriggered: page.setDeinterlace(!page.deinterlace) }
            MenuSeparator {}
            ChaosMenuItem { text: "Screenshot"; shortcut: "S"; enabled: page.active; onTriggered: mpv.screenshot() }
            ChaosMenuItem {
                text: "Preview on the seek bar"
                checkable: true; checked: page.previewEnabled
                onTriggered: { page.previewEnabled = !page.previewEnabled; app.setSetting("player/seekPreview", page.previewEnabled) }
            }
            // Escape hatch for graphics drivers that give a black picture with GPU decoding
            ChaosMenuItem {
                text: "Hardware decoding"
                checkable: true
                checked: app.hardwareDecoding
                onTriggered: {
                    app.hardwareDecoding = !app.hardwareDecoding
                    mpv.setMpvProperty("hwdec", app.hardwareDecoding ? "auto-safe" : "no")
                    osd.show(app.hardwareDecoding ? "Hardware decoding on" : "Hardware decoding off")
                }
            }
        }
        ChaosMenu {
            title: "Window"
            ChaosMenuItem { text: "Always on top"; shortcut: "Ctrl+T"; checkable: true; checked: app.alwaysOnTop; onTriggered: page.toggleOnTop() }
            ChaosMenuItem { text: "Mini player"; shortcut: "Ctrl+M"; checkable: true; checked: page.mini; onTriggered: page.setMini(!page.mini) }
            ChaosMenuItem { text: "Lock"; shortcut: "Ctrl+L"; enabled: page.active; onTriggered: page.setLocked(true) }
            ChaosMenuItem {
                text: "Magnetic screen edges"
                checkable: true
                checked: windowCtl.magnetic
                onTriggered: { windowCtl.magnetic = !windowCtl.magnetic; osd.show(windowCtl.magnetic ? "Magnetic edges on" : "Magnetic edges off") }
            }
            ChaosMenu {
                title: "Window size on play"
                ChaosMenuItem {
                    text: "Keep current size"
                    checkable: true; checked: windowCtl.sizeMode === "keep"
                    onTriggered: { windowCtl.sizeMode = "keep"; osd.show("Window keeps its size for every video") }
                }
                ChaosMenuItem {
                    text: "Always use my size" + (windowCtl.customSizeText.length > 0 ? "  (" + windowCtl.customSizeText + ")" : "")
                    checkable: true; checked: windowCtl.sizeMode === "custom"
                    onTriggered: { windowCtl.sizeMode = "custom"; osd.show("Every video opens at " + windowCtl.customSizeText) }
                }
                ChaosMenuItem {
                    text: "Fit to video (" + Math.round(windowCtl.fitScale * 100) + "%)"
                    checkable: true; checked: windowCtl.sizeMode === "fit"
                    onTriggered: { windowCtl.sizeMode = "fit"; osd.show("Window fits each video") }
                }
                MenuSeparator {}
                ChaosMenuItem {
                    text: "Use current window size as my size"
                    onTriggered: { windowCtl.useCurrentSizeAsCustom(); osd.show("My size: " + windowCtl.customSizeText) }
                }
                ChaosMenuItem { text: "Set my size…"; onTriggered: page.customSizeRequested() }
                ChaosMenu {
                    title: "Fit scale"
                    Repeater {
                        model: [0.5, 0.75, 1.0, 1.5, 2.0]
                        ChaosMenuItem {
                            required property var modelData
                            text: Math.round(modelData * 100) + "%"
                            checkable: true; checked: Math.abs(windowCtl.fitScale - modelData) < 0.001
                            onTriggered: windowCtl.fitScale = modelData
                        }
                    }
                }
            }
            MenuSeparator {}
            ChaosMenuItem { text: "Video size 50%"; shortcut: "Alt+1"; onTriggered: page.setWindowScale(0.5) }
            ChaosMenuItem { text: "Video size 100%"; shortcut: "Alt+2"; onTriggered: page.setWindowScale(1.0) }
            ChaosMenuItem { text: "Video size 150%"; shortcut: "Alt+3"; onTriggered: page.setWindowScale(1.5) }
            ChaosMenuItem { text: "Video size 200%"; shortcut: "Alt+4"; onTriggered: page.setWindowScale(2.0) }
            ChaosMenuItem { text: "Center on screen"; onTriggered: windowCtl.center() }
            ChaosMenuItem {
                text: "Open videos in this window"
                checkable: true
                checked: app.setting("player/singleInstance", true)
                onTriggered: {
                    const on = !app.setting("player/singleInstance", true)
                    app.setSetting("player/singleInstance", on)
                    osd.show(on ? "New videos open in this window" : "Each video opens a new window (from next start)")
                }
            }
            MenuSeparator {}
            ChaosMenuItem { text: page.fullscreen ? "Exit fullscreen" : "Fullscreen"; shortcut: "Enter"; onTriggered: page.toggleFullscreen() }
        }
        MenuSeparator {}
        ChaosMenuItem { text: "About CPlay"; onTriggered: osd.show("CPlay " + app.version + "  ·  mpv " + (mpv.mpvProperty("mpv-version") || "").replace("mpv ", "")) }
        ChaosMenuItem { text: "Exit"; shortcut: "Ctrl+Q"; onTriggered: page.quitRequested() }
    }

    // ---- hotkeys (spec 4.2 / 8.3) ---------------------------------------------
    Shortcut { sequence: "Space"; enabled: !page.locked && (page.active); onActivated: { mpv.togglePause(); page.poke() } }
    Shortcut { sequence: "K"; enabled: !page.locked && (page.active); onActivated: mpv.togglePause() }
    Shortcut { sequence: "Right"; enabled: !page.locked && (page.active); onActivated: { mpv.seekBy(10000); page.poke() } }
    Shortcut { sequence: "Left"; enabled: !page.locked && (page.active); onActivated: { mpv.seekBy(-10000); page.poke() } }
    Shortcut { sequence: "Shift+Right"; enabled: !page.locked && (page.active); onActivated: mpv.seekBy(60000) }
    Shortcut { sequence: "Shift+Left"; enabled: !page.locked && (page.active); onActivated: mpv.seekBy(-60000) }
    Shortcut { enabled: !page.locked && page.active; sequence: "Up"; onActivated: page.changeVolume(5) }
    Shortcut { enabled: !page.locked && page.active; sequence: "Down"; onActivated: page.changeVolume(-5) }
    Shortcut { enabled: !page.locked && page.active; sequence: "M"; onActivated: { mpv.setMuted(!mpv.muted); osd.show(mpv.muted ? "Sound on" : "Muted") } }
    Shortcut { enabled: !page.locked && page.active; sequences: ["Return", "Enter", "F"]; onActivated: page.toggleFullscreen() }
    Shortcut { sequence: "Esc"; enabled: !page.locked && (page.fullscreen); onActivated: page.toggleFullscreen() }
    Shortcut { enabled: !page.locked; sequence: "Ctrl+T"; onActivated: page.toggleOnTop() }
    Shortcut { enabled: !page.locked; sequence: "Menu"; onActivated: page.openMainMenuAt(page.width / 3, page.height / 5) }
    Shortcut { enabled: !page.locked && page.active; sequence: "Alt+1"; onActivated: page.setWindowScale(0.5) }
    Shortcut { enabled: !page.locked && page.active; sequence: "Alt+2"; onActivated: page.setWindowScale(1.0) }
    Shortcut { enabled: !page.locked && page.active; sequence: "Alt+3"; onActivated: page.setWindowScale(1.5) }
    Shortcut { enabled: !page.locked && page.active; sequence: "Alt+4"; onActivated: page.setWindowScale(2.0) }
    Shortcut { enabled: !page.locked && page.active; sequence: "]"; onActivated: page.setSpeed(app.stepSpeed(mpv.speed, true)) }
    Shortcut { enabled: !page.locked && page.active; sequence: "["; onActivated: page.setSpeed(app.stepSpeed(mpv.speed, false)) }
    Shortcut { enabled: !page.locked && page.active; sequence: "Backspace"; onActivated: page.setSpeed(1.0) }
    Shortcut { sequence: "."; enabled: !page.locked && (page.active); onActivated: mpv.frameStep(true) }
    Shortcut { sequence: ","; enabled: !page.locked && (page.active); onActivated: mpv.frameStep(false) }
    Shortcut { sequence: "S"; enabled: !page.locked && (page.active); onActivated: mpv.screenshot() }
    Shortcut { sequence: "V"; enabled: !page.locked && (page.active); onActivated: page.cycleSubtitles() }
    Shortcut { sequence: "Home"; enabled: !page.locked && (page.active); onActivated: mpv.seekTo(0) }
    Shortcut { enabled: !page.locked && page.active; sequences: ["PgDown", "N"]; onActivated: page.next() }
    Shortcut { sequences: ["PgUp", "P"]; enabled: !page.locked && (page.active); onActivated: page.previous() }

    // Phase 2 keys
    Shortcut { enabled: !page.locked && page.active; sequence: "L"; onActivated: page.pressAb() }
    Shortcut { enabled: !page.locked && page.active; sequence: "B"; onActivated: page.addBookmark() }
    Shortcut { enabled: !page.locked && page.active; sequence: "Ctrl+Right"; onActivated: page.jumpBookmark(true) }
    Shortcut { enabled: !page.locked && page.active; sequence: "Ctrl+Left"; onActivated: page.jumpBookmark(false) }
    Shortcut { enabled: !page.locked && page.active; sequences: ["Ctrl+=", "Ctrl++"]; onActivated: page.setAudioDelay(tools.stepDelay(page.audioDelayMs, true)) }
    Shortcut { enabled: !page.locked && page.active; sequence: "Ctrl+-"; onActivated: page.setAudioDelay(tools.stepDelay(page.audioDelayMs, false)) }
    Shortcut { enabled: !page.locked && page.active; sequences: ["Alt+=", "Alt++"]; onActivated: page.setSubDelay(tools.stepDelay(page.subDelayMs, true)) }
    Shortcut { enabled: !page.locked && page.active; sequence: "Alt+-"; onActivated: page.setSubDelay(tools.stepDelay(page.subDelayMs, false)) }
    Shortcut { enabled: !page.locked && page.active; sequence: "Alt+Up"; onActivated: page.setSubScale(tools.stepSubScale(page.subScale, true)) }
    Shortcut { enabled: !page.locked && page.active; sequence: "Alt+Down"; onActivated: page.setSubScale(tools.stepSubScale(page.subScale, false)) }
    Shortcut { enabled: !page.locked && page.active; sequence: "Ctrl+Up"; onActivated: page.setZoom(tools.stepZoom(page.zoom, true)) }
    Shortcut { enabled: !page.locked && page.active; sequence: "Ctrl+Down"; onActivated: page.setZoom(tools.stepZoom(page.zoom, false)) }
    Shortcut { enabled: !page.locked && page.active; sequence: "Ctrl+R"; onActivated: page.rotate() }
    Shortcut { enabled: !page.locked && page.active; sequence: "D"; onActivated: page.setDeinterlace(!page.deinterlace) }
    Shortcut { enabled: !page.locked && page.active; sequence: "Ctrl+G"; onActivated: page.showControlBox = !page.showControlBox }
    Shortcut { enabled: !page.locked && page.active; sequence: "Ctrl+P"; onActivated: page.showPlaylist = !page.showPlaylist }
    Shortcut { enabled: !page.locked && page.active; sequence: "Ctrl+M"; onActivated: page.setMini(!page.mini) }
    Shortcut { enabled: !page.locked && page.active; sequence: "Ctrl+L"; onActivated: page.setLocked(true) }
    Shortcut { enabled: !page.locked; sequence: "Ctrl+Shift+O"; onActivated: page.openFolderRequested() }
}
