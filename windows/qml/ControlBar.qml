import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// Control bar of the classic desktop-player skin: full-width seek bar on top,
// transport buttons and time on the left, tracks / tools / volume on the right.
Rectangle {
    id: bar
    required property var host   // PlayerPage: player functions and state
    readonly property var mpv: host.player
    readonly property bool active: host.currentLocation.length > 0
    readonly property alias seeking: seek.dragging
    // Narrow windows drop the less important buttons (all still in the menu,
    // on the keyboard and on the mouse wheel), widest first
    readonly property bool roomForExtras: width >= 920      // volume %, screenshot, control box, lock
    readonly property bool roomForTransport: width >= 780   // stop, open
    readonly property bool roomForTracks: width >= 680      // previous/next, audio, pin
    readonly property bool roomForTools: width >= 560       // speed, subtitles, playlist, volume slider
    signal menuRequested(Item anchor)

    height: Theme.controlBarHeight
    gradient: Gradient {
        GradientStop { position: 0; color: Theme.chromeTop }
        GradientStop { position: 1; color: Theme.chromeBottom }
    }
    Rectangle { anchors { left: parent.left; right: parent.right; top: parent.top } height: 1; color: Theme.chromeBorder }

    ColumnLayout {
        anchors { fill: parent; leftMargin: 8; rightMargin: 8; topMargin: 4; bottomMargin: 4 }
        spacing: 0

        SeekBar {
            id: seek
            Layout.fillWidth: true
            Layout.preferredHeight: 20
            value: mpv.duration > 0 ? mpv.position / mpv.duration : 0
            labelFor: (f) => app.formatClock(f * mpv.duration)
            enabled: bar.active && mpv.duration > 0
            // A-B repeat in amber, bookmarks in white, preview frame on hover
            marks: mpv.duration > 0 ? [bar.host.abA, bar.host.abB].filter(v => v >= 0).map(v => v / mpv.duration) : []
            minorMarks: mpv.duration > 0 ? bar.host.bookmarkList.map(b => b.positionMs / mpv.duration) : []
            previewSource: bar.host.previewEnabled ? bar.host.currentLocation : ""
            previewDurationMs: mpv.duration
            Accessible.name: "Seek bar"
            onReleased: (f) => { mpv.seekTo(f * mpv.duration); bar.host.poke() }
        }

        RowLayout {
            Layout.fillWidth: true
            Layout.minimumWidth: 0
            spacing: 1

            // Play/pause: the one round accent button
            AbstractButton {
                id: playButton
                implicitWidth: 38; implicitHeight: 38
                hoverEnabled: true
                enabled: bar.active
                Accessible.name: mpv.paused ? "Play" : "Pause"
                ToolTip.visible: hovered
                ToolTip.delay: 700
                ToolTip.text: mpv.paused ? "Play (Space)" : "Pause (Space)"
                background: Rectangle {
                    radius: width / 2
                    color: !playButton.enabled ? Theme.surface3 : playButton.pressed ? Qt.darker(Theme.accent, 1.2)
                         : playButton.hovered ? Qt.lighter(Theme.accent, 1.12) : Theme.accent
                }
                contentItem: Icon { name: !bar.active || mpv.paused ? (mpv.ended ? "replay" : "play_arrow") : "pause"; size: 26; color: "#0B0D10" }
                onClicked: mpv.togglePause()
            }
            IconButton { iconName: "stop"; tip: "Stop"; visible: bar.roomForTransport; enabled: bar.active; onClicked: bar.host.close() }
            IconButton { iconName: "skip_previous"; tip: "Previous (Page Up)"; visible: bar.roomForTracks; enabled: bar.active; onClicked: bar.host.previous() }
            IconButton { iconName: "skip_next"; tip: "Next (Page Down)"; visible: bar.roomForTracks; enabled: bar.host.hasNext; onClicked: bar.host.next() }
            IconButton { iconName: "eject"; tip: "Open file (Ctrl+O)"; visible: bar.roomForTransport; onClicked: bar.host.openFileRequested() }

            Text {
                Layout.leftMargin: 10
                text: !bar.active ? "00:00:00 / 00:00:00"
                     : app.formatClock(seek.dragging ? seek.dragValue * mpv.duration : mpv.position) + " / "
                       + (app.showRemaining ? app.formatClockRemaining(mpv.duration, mpv.position) : app.formatClock(mpv.duration))
                color: Theme.chromeText
                font.family: "Consolas"
                font.pixelSize: 13
                MouseArea {
                    anchors.fill: parent
                    cursorShape: Qt.PointingHandCursor
                    onClicked: app.showRemaining = !app.showRemaining
                }
            }

            // Stream info, like the status area of classic players
            Text {
                Layout.leftMargin: 10
                Layout.fillWidth: true
                Layout.minimumWidth: 0
                Layout.preferredWidth: 1
                elide: Text.ElideRight
                visible: bar.active && mpv.videoWidth > 0
                text: mpv.videoWidth + "×" + mpv.videoHeight
                      + (mpv.hwdec.length > 0 && mpv.hwdec !== "no" ? "  ·  HW" : "  ·  SW")
                      + (Math.abs(mpv.speed - 1) > 0.001 ? "  ·  " + app.formatSpeed(mpv.speed) : "")
                      + (bar.host.abA >= 0 ? "  ·  " + (bar.host.abB >= 0 ? "A-B" : "A-") : "")
                      + (bar.host.sleepRemainingMs > 0 ? "  ·  " + tools.sleepLabel(bar.host.sleepRemainingMs)
                         : bar.host.sleepAtEnd ? "  ·  Sleep at end" : "")
                color: Theme.textMuted
                font.family: Theme.fontFamily
                font.pixelSize: 11
            }
            Item { Layout.fillWidth: true; Layout.minimumWidth: 0; visible: !(bar.active && mpv.videoWidth > 0) }

            IconButton {
                iconName: "subtitles"
                visible: bar.roomForTools
                checked: mpv.subtitleId > 0
                enabled: bar.active
                tip: "Subtitles (V cycles)"
                onClicked: bar.host.openSubtitleMenu(this)
            }
            IconButton {
                iconName: "music_note"
                visible: bar.roomForTracks
                enabled: bar.active && mpv.audioTracks.length > 0
                tip: "Audio track"
                onClicked: bar.host.openAudioMenu(this)
            }
            IconButton {
                label: app.formatSpeed(mpv.speed)
                visible: bar.roomForTools
                checked: Math.abs(mpv.speed - 1) > 0.001
                enabled: bar.active
                tip: "Playback speed ([ and ])"
                onClicked: bar.host.openSpeedMenu(this)
            }
            IconButton { iconName: "photo_camera"; visible: bar.roomForExtras; enabled: bar.active; tip: "Screenshot (S)"; onClicked: mpv.screenshot() }
            IconButton { iconName: "tune"; visible: bar.roomForExtras; checked: bar.host.showControlBox; tip: "Control box: picture, audio, subtitles (Ctrl+G)"; onClicked: bar.host.showControlBox = !bar.host.showControlBox }
            IconButton { iconName: "playlist_play"; visible: bar.roomForTools; checked: bar.host.showPlaylist; tip: "Playlist (Ctrl+P)"; onClicked: bar.host.showPlaylist = !bar.host.showPlaylist }
            IconButton { iconName: "lock"; visible: bar.roomForExtras; enabled: bar.active; tip: "Lock: ignore mouse and keys (Ctrl+L)"; onClicked: bar.host.setLocked(true) }
            IconButton {
                iconName: "push_pin"
                visible: bar.roomForTracks       // the title bar keeps its own pin
                checked: app.alwaysOnTop
                tip: app.alwaysOnTop ? "Always on top: on (Ctrl+T)" : "Always on top (Ctrl+T)"
                onClicked: bar.host.toggleOnTop()
            }
            IconButton { iconName: "menu"; tip: "Menu"; onClicked: bar.menuRequested(this) }

            IconButton {
                Layout.leftMargin: 6
                iconName: mpv.muted || mpv.volume === 0 ? "volume_off" : mpv.volume < 50 ? "volume_down" : "volume_up"
                tip: mpv.muted ? "Unmute (M)" : "Mute (M)"
                onClicked: mpv.setMuted(!mpv.muted)
            }
            SeekBar {
                visible: bar.roomForTools
                Layout.preferredWidth: bar.roomForTransport ? 96 : 70
                Layout.preferredHeight: 20
                live: true
                value: mpv.muted ? 0 : mpv.volume / 150
                Accessible.name: "Volume"
                labelFor: (f) => Math.round(f * 150) + "%"
                onMoved: (f) => { mpv.setVolume(Math.round(f * 150)); if (mpv.muted) mpv.setMuted(false) }
                onReleased: (f) => app.volume = Math.round(f * 150)
            }
            Text {
                visible: bar.roomForExtras
                Layout.preferredWidth: 38
                horizontalAlignment: Text.AlignRight
                text: (mpv.muted ? 0 : Math.round(mpv.volume)) + "%"
                color: Theme.chromeText
                font.family: Theme.fontFamily
                font.pixelSize: 12
            }
            IconButton {
                iconName: bar.host.fullscreen ? "fullscreen_exit" : "fullscreen"
                tip: bar.host.fullscreen ? "Exit fullscreen (Esc)" : "Fullscreen (Enter)"
                onClicked: bar.host.toggleFullscreen()
            }
        }
    }
}
