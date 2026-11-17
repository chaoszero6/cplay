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
    readonly property bool active: currentLocation.length > 0
    readonly property bool hasNext: queueIndex >= 0 && queueIndex < queue.length - 1
    readonly property bool hasPrevious: queueIndex > 0
    readonly property bool fullscreen: Window.window !== null && Window.window.visibility === Window.FullScreen
    readonly property bool menusOpen: mainMenu.opened || subtitleMenu.opened || audioMenu.opened || speedMenu.opened
    property bool controlsShown: true
    readonly property bool chromeShown: !fullscreen || controlsShown || mpv.paused || menusOpen || controlBar.seeking
                                        || !active || mpv.errorString.length > 0
    readonly property string mediaTitle: active ? (mpv.title.length > 0 ? mpv.title : app.displayName(currentLocation)) : ""

    signal closeRequested()
    signal toggleFullscreen()
    signal openFileRequested()
    signal openLinkRequested()
    signal subtitleFileRequested()
    signal quitRequested()
    signal customSizeRequested()

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
        const start = app.startPositionFor(currentLocation)
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
        onCloseRequested: page.quitRequested()
    }

    // ---- video ---------------------------------------------------------------
    Item {
        id: videoArea
        anchors {
            left: parent.left; right: parent.right
            top: page.fullscreen ? parent.top : titleBar.bottom
            bottom: page.fullscreen ? parent.bottom : controlBar.top
        }
        // No clip here: clipping the mpv framebuffer item leaves the picture black

        Rectangle { anchors.fill: parent; color: "black" }

        MpvPlayer {
            id: mpv
            anchors.fill: parent
            Component.onCompleted: if (!app.hardwareDecoding) setMpvProperty("hwdec", "no")
            onFileLoaded: page.poke()
            onEndReached: {
                page.saveProgress(true)
                if (page.hasNext) page.next()
            }
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
            cursorShape: page.chromeShown || !page.fullscreen ? Qt.ArrowCursor : Qt.BlankCursor
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

        IdleScreen {
            anchors.fill: parent
            visible: !page.active
            onOpenFiles: page.openFileRequested()
            onOpenLink: page.openLinkRequested()
            onPlayLocation: (location) => page.playQueue([location], 0)
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
            anchors { left: parent.left; top: parent.top; leftMargin: 16; topMargin: page.fullscreen && page.chromeShown ? titleBar.height + 12 : 12 }
            z: 4
        }
    }

    // ---- control bar ----------------------------------------------------------
    ControlBar {
        id: controlBar
        z: 5
        host: page
        anchors { left: parent.left; right: parent.right }
        y: page.chromeShown ? page.height - height : page.height
        Behavior on y { NumberAnimation { duration: Theme.fadeMs; easing.type: Easing.OutCubic } }
        onMenuRequested: (anchor) => page.openMainMenu(anchor, false)
        HoverHandler { onHoveredChanged: if (hovered) page.poke() }
    }

    Timer { interval: 5000; repeat: true; running: !mpv.paused && page.active; onTriggered: page.saveProgress(false) }
    Timer { id: hideTimer; interval: Theme.autoHideMs; onTriggered: page.controlsShown = false }
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
        }
        ChaosMenu {
            title: "Audio"
            Repeater {
                model: mpv.audioTracks
                ChaosMenuItem { required property var modelData; text: modelData.label; checkable: true; checked: modelData.id === mpv.audioId; onTriggered: mpv.setAudioTrack(modelData.id) }
            }
        }
        ChaosMenu {
            title: "Video"
            ChaosMenuItem { text: "Screenshot"; shortcut: "S"; enabled: page.active; onTriggered: mpv.screenshot() }
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
            MenuSeparator {}
            ChaosMenuItem { text: page.fullscreen ? "Exit fullscreen" : "Fullscreen"; shortcut: "Enter"; onTriggered: page.toggleFullscreen() }
        }
        MenuSeparator {}
        ChaosMenuItem { text: "About CPlay"; onTriggered: osd.show("CPlay " + app.version + "  ·  mpv " + (mpv.mpvProperty("mpv-version") || "").replace("mpv ", "")) }
        ChaosMenuItem { text: "Exit"; shortcut: "Ctrl+Q"; onTriggered: page.quitRequested() }
    }

    // ---- hotkeys (spec 4.2 / 8.3) ---------------------------------------------
    Shortcut { sequence: "Space"; enabled: page.active; onActivated: { mpv.togglePause(); page.poke() } }
    Shortcut { sequence: "K"; enabled: page.active; onActivated: mpv.togglePause() }
    Shortcut { sequence: "Right"; enabled: page.active; onActivated: { mpv.seekBy(10000); page.poke() } }
    Shortcut { sequence: "Left"; enabled: page.active; onActivated: { mpv.seekBy(-10000); page.poke() } }
    Shortcut { sequence: "Shift+Right"; enabled: page.active; onActivated: mpv.seekBy(60000) }
    Shortcut { sequence: "Shift+Left"; enabled: page.active; onActivated: mpv.seekBy(-60000) }
    Shortcut { sequence: "Up"; onActivated: page.changeVolume(5) }
    Shortcut { sequence: "Down"; onActivated: page.changeVolume(-5) }
    Shortcut { sequence: "M"; onActivated: { mpv.setMuted(!mpv.muted); osd.show(mpv.muted ? "Sound on" : "Muted") } }
    Shortcut { sequences: ["Return", "Enter", "F"]; onActivated: page.toggleFullscreen() }
    Shortcut { sequence: "Esc"; enabled: page.fullscreen; onActivated: page.toggleFullscreen() }
    Shortcut { sequence: "Ctrl+T"; onActivated: page.toggleOnTop() }
    Shortcut { sequence: "Menu"; onActivated: page.openMainMenuAt(page.width / 3, page.height / 5) }
    Shortcut { sequence: "Alt+1"; onActivated: page.setWindowScale(0.5) }
    Shortcut { sequence: "Alt+2"; onActivated: page.setWindowScale(1.0) }
    Shortcut { sequence: "Alt+3"; onActivated: page.setWindowScale(1.5) }
    Shortcut { sequence: "Alt+4"; onActivated: page.setWindowScale(2.0) }
    Shortcut { sequence: "]"; onActivated: page.setSpeed(app.stepSpeed(mpv.speed, true)) }
    Shortcut { sequence: "["; onActivated: page.setSpeed(app.stepSpeed(mpv.speed, false)) }
    Shortcut { sequence: "Backspace"; onActivated: page.setSpeed(1.0) }
    Shortcut { sequence: "."; enabled: page.active; onActivated: mpv.frameStep(true) }
    Shortcut { sequence: ","; enabled: page.active; onActivated: mpv.frameStep(false) }
    Shortcut { sequence: "S"; enabled: page.active; onActivated: mpv.screenshot() }
    Shortcut { sequence: "V"; enabled: page.active; onActivated: page.cycleSubtitles() }
    Shortcut { sequence: "Home"; enabled: page.active; onActivated: mpv.seekTo(0) }
    Shortcut { sequences: ["PgDown", "N"]; onActivated: page.next() }
    Shortcut { sequences: ["PgUp", "P"]; enabled: page.active; onActivated: page.previous() }
}
