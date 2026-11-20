import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Dialogs
import QtQuick.Layouts

// Frameless player window with its own title bar (TitleBar.qml), edge resize
// grips and magnetic screen edges (WindowController).
ApplicationWindow {
    id: win
    width: 1100
    height: 680
    minimumWidth: 480
    minimumHeight: 300
    visible: true
    color: "#0B0D10"
    // Minimize/maximize/system-menu hints keep taskbar click-to-minimize and
    // Alt+Space working on a frameless window
    flags: Qt.Window | Qt.FramelessWindowHint | Qt.WindowMinMaxButtonsHint | Qt.WindowSystemMenuHint
    title: player.active ? player.mediaTitle + " - CPlay" : "CPlay"
    font.family: Theme.fontFamily

    // Basic style controls (buttons, menus, dialogs, fields) in CPlay colours
    palette {
        window: Theme.menuBg
        windowText: Theme.text
        base: Theme.surface2
        alternateBase: Theme.surface
        text: Theme.text
        button: Theme.surface2
        buttonText: Theme.text
        dark: Theme.accent          // highlighted buttons
        brightText: Theme.bg        // text on highlighted buttons
        mid: Theme.surface3
        light: Theme.surface3
        midlight: Theme.surface3
        highlight: Theme.accent
        highlightedText: Theme.bg
        placeholderText: Theme.textMuted
        toolTipBase: Theme.menuBg
        toolTipText: Theme.text
    }

    readonly property bool framed: visibility !== Window.FullScreen && !windowCtl.maximized

    function toggleFullscreen() {
        if (visibility === Window.FullScreen) showNormal()
        else showFullScreen()
    }

    /** Files from the open dialog, drag and drop, "Open with" or a second launch. */
    function openLocations(locations, append) {
        let media = []
        locations.forEach(l => {
            if (app.isPlaylist(l)) media = media.concat(tools.loadPlaylist(app.toUrl(l)))
            else if (app.isMedia(l) && !app.isSubtitle(l)) media.push(l)
        })
        const subs = locations.filter(l => app.isSubtitle(l))
        if (media.length > 0) {
            if (append) player.addToQueue(media)
            else player.playLocations(media)
        }
        if (subs.length > 0 && player.active) subs.forEach(s => player.player.addSubtitle(s))
    }

    // A second launch ("Open with", double-click in Explorer) lands here
    Connections {
        target: app
        function onOpenRequested(files) {
            if (files.length > 0) win.openLocations(files, false)
            if (win.visibility === Window.Minimized) win.showNormal()
            win.raise()
            win.requestActivate()
        }
    }

    onClosing: {
        // Leaving from the mini player: remember the normal size and the user's own pin setting
        if (player.mini) player.setMini(false)
        player.saveProgress(player.player.ended)
        player.reportStop()
        windowCtl.saveGeometry()
    }

    // Developer hook used by the screenshot checks (CHAOSPLAY_SNAPSHOT_JS)
    function runDevScript(script) { eval(script) }

    Component.onCompleted: {
        Theme.lightPages = app.setting("app/theme", "dark") === "light"
        // The saved size/position is restored from C++ once the window is shown
        if (app.startupFiles.length > 0) openLocations(app.startupFiles, false)
    }

    // Remember every size and position the user gives the window (resize from an
    // edge, drag, snap): saved shortly after it stops changing
    Timer { id: saveGeometryTimer; interval: 600; onTriggered: if (!player.mini) windowCtl.saveGeometry() }
    onWidthChanged: saveGeometryTimer.restart()
    onHeightChanged: saveGeometryTimer.restart()
    onXChanged: saveGeometryTimer.restart()
    onYChanged: saveGeometryTimer.restart()

    PlayerPage {
        id: player
        anchors { fill: parent; margins: win.framed ? 1 : 0 }
        onToggleFullscreen: win.toggleFullscreen()
        onOpenFileRequested: fileDialog.open()
        onOpenLinkRequested: linkDialog.open()
        onSubtitleFileRequested: subtitleDialog.open()
        onQuitRequested: win.close()
        onCustomSizeRequested: sizeDialog.open()
        onAddFilesRequested: addFilesDialog.open()
        onAddFolderRequested: { folderDialog.append = true; folderDialog.open() }
        onOpenFolderRequested: { folderDialog.append = false; folderDialog.open() }
        onLoadPlaylistRequested: loadPlaylistDialog.open()
        onSavePlaylistRequested: savePlaylistDialog.open()
        onCloseRequested: if (win.visibility === Window.FullScreen) win.showNormal()
    }

    // 1 px window border (the frameless window has no system frame)
    Rectangle {
        anchors.fill: parent
        visible: win.framed
        color: "transparent"
        border.color: windowCtl.dragging ? Theme.accent : Theme.chromeBorder
        z: 20
    }

    ResizeGrips { visible: win.framed && !player.locked; z: 30 }

    // Drag videos (and subtitle files) anywhere onto the window
    DropArea {
        anchors.fill: parent
        onDropped: (drop) => {
            if (!drop.hasUrls) return
            win.openLocations(drop.urls.map(u => app.toLocation(u)), false)
            drop.accept()
        }
        Rectangle {
            anchors { fill: parent; margins: 12 }
            visible: parent.containsDrag
            color: "#334DA3FF"
            radius: 6
            border { color: Theme.accent; width: 2 }
            Text { anchors.centerIn: parent; text: "Drop to play"; color: Theme.text; font.pixelSize: 22 }
        }
    }

    FileDialog {
        id: fileDialog
        title: "Open videos"
        fileMode: FileDialog.OpenFiles
        nameFilters: app.fileFilters
        currentFolder: app.lastFolder()
        onAccepted: {
            app.setLastFolder(currentFolder)
            win.openLocations(selectedFiles.map(u => app.toLocation(u)), false)
        }
    }

    FileDialog {
        id: addFilesDialog
        title: "Add to the playlist"
        fileMode: FileDialog.OpenFiles
        nameFilters: app.fileFilters
        currentFolder: app.lastFolder()
        onAccepted: { app.setLastFolder(currentFolder); win.openLocations(selectedFiles.map(u => app.toLocation(u)), true) }
    }

    FolderDialog {
        id: folderDialog
        property bool append: false
        title: append ? "Add a folder to the playlist" : "Play a folder"
        currentFolder: app.lastFolder()
        onAccepted: {
            const files = tools.mediaInFolder(selectedFolder)
            if (files.length === 0) { player.osdMessage("No videos in that folder"); return }
            if (append) player.addToQueue(files)
            else player.playQueue(files, 0)
        }
    }

    FileDialog {
        id: loadPlaylistDialog
        title: "Open a playlist"
        fileMode: FileDialog.OpenFile
        nameFilters: ["Playlists (*.m3u *.m3u8)", "All files (*)"]
        onAccepted: {
            const files = tools.loadPlaylist(selectedFile)
            if (files.length === 0) { player.osdMessage("That playlist is empty or unreadable"); return }
            player.playQueue(files, 0)
        }
    }

    FileDialog {
        id: savePlaylistDialog
        title: "Save the playlist"
        fileMode: FileDialog.SaveFile
        defaultSuffix: "m3u8"
        nameFilters: ["Playlist (*.m3u8)", "Playlist (*.m3u)"]
        onAccepted: player.osdMessage(tools.savePlaylist(selectedFile, player.queue, player.queue.map(l => app.displayName(l)))
                                      ? "Playlist saved" : "Could not save the playlist")
    }

    FileDialog {
        id: subtitleDialog
        title: "Load subtitles"
        fileMode: FileDialog.OpenFile
        nameFilters: ["Subtitles (*.srt *.ass *.ssa *.vtt *.sub *.sup *.idx *.smi)", "All files (*)"]
        onAccepted: player.player.addSubtitle(app.toLocation(selectedFile))
    }

    Dialog {
        id: linkDialog
        title: "Open a link"
        modal: true
        anchors.centerIn: parent
        width: Math.min(560, win.width - 40)
        standardButtons: Dialog.Open | Dialog.Cancel
        onOpened: { linkField.text = ""; linkField.forceActiveFocus() }
        onAccepted: if (linkField.text.trim().length > 0) player.playQueue([linkField.text.trim()], 0)
        ColumnLayout {
            anchors.fill: parent
            Text { text: "Video, HLS (.m3u8) or DASH (.mpd) link"; color: Theme.textMuted }
            TextField {
                id: linkField
                Layout.fillWidth: true
                placeholderText: "https://example.com/video.mp4"
                onAccepted: linkDialog.accept()
            }
        }
    }

    // "Set my size…": the window size every video opens at
    Dialog {
        id: sizeDialog
        title: "Window size for every video"
        modal: true
        anchors.centerIn: parent
        width: Math.min(420, win.width - 40)
        standardButtons: Dialog.Ok | Dialog.Cancel
        onOpened: {
            sizeField.text = windowCtl.customSizeText.length > 0 ? windowCtl.customSizeText.replace("×", "x") : win.width + "x" + win.height
            sizeError.visible = false
            sizeField.forceActiveFocus()
            sizeField.selectAll()
        }
        onAccepted: {
            if (!windowCtl.setCustomSizeText(sizeField.text)) { sizeError.visible = true; open(); return }
        }
        ColumnLayout {
            anchors.fill: parent
            spacing: 8
            Text { text: "Width x height of the whole window, e.g. 1280x720"; color: Theme.textMuted }
            TextField { id: sizeField; Layout.fillWidth: true; onAccepted: sizeDialog.accept() }
            Text { id: sizeError; visible: false; text: "Type a size like 1280x720 (at least 100x100)"; color: Theme.danger }
            Text { text: "Every video then opens at this size, whatever its resolution."; color: Theme.textMuted; wrapMode: Text.Wrap; Layout.fillWidth: true }
        }
    }

    Shortcut { enabled: !player.locked; sequence: "Ctrl+O"; onActivated: fileDialog.open() }
    Shortcut { enabled: !player.locked; sequence: "Ctrl+U"; onActivated: linkDialog.open() }
    Shortcut { enabled: !player.locked; sequence: "Ctrl+Q"; onActivated: win.close() }
}
