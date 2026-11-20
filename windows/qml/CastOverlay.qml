import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// Remote control shown over the video while it plays on a TV.
Rectangle {
    id: overlay
    signal stopRequested()
    color: "#0B0D10"

    MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons; onWheel: (w) => w.accepted = true }

    ColumnLayout {
        anchors.centerIn: parent
        width: Math.min(parent.width - 48, 520)
        spacing: 14
        Icon { Layout.alignment: Qt.AlignHCenter; name: "cast_connected"; size: 64; color: Theme.accent }
        Text {
            Layout.fillWidth: true; horizontalAlignment: Text.AlignHCenter; wrapMode: Text.Wrap
            text: tv.state === "connecting" ? "Connecting to " + tv.deviceName + "…"
                  : tv.state === "buffering" ? "Starting on " + tv.deviceName + "…"
                  : tv.state === "error" ? tv.error
                  : "Playing on " + tv.deviceName
            color: tv.state === "error" ? Theme.danger : Theme.text; font.family: Theme.fontFamily; font.pixelSize: 18; font.weight: Font.DemiBold
        }
        Text { Layout.fillWidth: true; horizontalAlignment: Text.AlignHCenter; elide: Text.ElideMiddle; text: tv.title; color: Theme.textMuted; font.family: Theme.fontFamily; font.pixelSize: 13 }
        SeekBar {
            Layout.fillWidth: true
            Layout.preferredHeight: 24
            value: tv.duration > 0 ? tv.position / tv.duration : 0
            labelFor: (f) => app.formatClock(f * tv.duration)
            onReleased: (f) => tv.seek(f * tv.duration)
        }
        RowLayout {
            Layout.fillWidth: true
            Text { text: app.formatClock(tv.position); color: Theme.chromeText; font.family: Theme.fontFamily; font.pixelSize: 12 }
            Item { Layout.fillWidth: true }
            Text { text: app.formatClock(tv.duration); color: Theme.chromeText; font.family: Theme.fontFamily; font.pixelSize: 12 }
        }
        RowLayout {
            Layout.alignment: Qt.AlignHCenter
            spacing: 14
            IconButton { iconName: "replay_10"; tip: "Back 10 seconds"; onClicked: tv.seek(Math.max(0, tv.position - 10000)) }
            IconButton { iconName: tv.state === "playing" ? "pause" : "play_arrow"; iconSize: 34; implicitWidth: 56; implicitHeight: 56; tip: "Play / pause (Space)"; onClicked: tv.togglePause() }
            IconButton { iconName: "forward_30"; tip: "Forward 30 seconds"; onClicked: tv.seek(tv.position + 30000) }
            IconButton { iconName: "stop"; tip: "Stop and continue on this PC"; onClicked: overlay.stopRequested() }
        }
        RowLayout {
            Layout.alignment: Qt.AlignHCenter
            spacing: 8
            Icon { name: "volume_up"; size: 18; color: Theme.chromeIcon }
            SeekBar {
                Layout.preferredWidth: 180
                Layout.preferredHeight: 20
                live: true
                value: tv.volume / 100
                labelFor: (f) => Math.round(f * 100) + "%"
                onReleased: (f) => tv.setVolume(Math.round(f * 100))
            }
            Text { text: "TV volume"; color: Theme.textMuted; font.family: Theme.fontFamily; font.pixelSize: 12 }
        }
    }
}
