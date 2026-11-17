import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// Playlist panel (Ctrl+P): the play queue. Double-click plays, right-click for
// move/remove, Delete removes; add files or a folder, save and load .m3u files.
Rectangle {
    id: panel
    required property var host        // PlayerPage
    property int selected: host.queueIndex
    signal addFilesRequested()
    signal addFolderRequested()
    signal loadRequested()
    signal saveRequested()

    width: 320
    color: "#F01A1E24"
    border.color: Theme.chromeBorder
    focus: visible

    MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons; onWheel: (w) => w.accepted = true }

    Keys.onDeletePressed: if (selected >= 0) host.removeFromQueue(selected)
    Keys.onUpPressed: selected = Math.max(0, selected - 1)
    Keys.onDownPressed: selected = Math.min(host.queue.length - 1, selected + 1)
    Keys.onReturnPressed: if (selected >= 0) host.playAt(selected)

    ColumnLayout {
        anchors { fill: parent; margins: 6 }
        spacing: 4

        RowLayout {
            Layout.fillWidth: true
            spacing: 2
            Text {
                Layout.fillWidth: true
                Layout.leftMargin: 4
                text: "Playlist  (" + panel.host.queue.length + ")"
                color: Theme.text; font.family: Theme.fontFamily; font.pixelSize: 13; font.weight: Font.DemiBold
            }
            IconButton {
                iconName: panel.host.repeatMode === "one" ? "repeat_one" : "repeat"
                checked: panel.host.repeatMode !== "off"
                iconSize: 18
                tip: panel.host.repeatMode === "off" ? "Repeat: off" : panel.host.repeatMode === "one" ? "Repeat: this video" : "Repeat: whole list"
                onClicked: panel.host.cycleRepeat()
            }
            IconButton { iconName: "shuffle"; iconSize: 18; tip: "Shuffle the list"; enabled: panel.host.queue.length > 2; onClicked: panel.host.shuffleQueue() }
            IconButton { iconName: "close"; iconSize: 18; tip: "Close (Ctrl+P)"; onClicked: panel.host.showPlaylist = false }
        }

        ListView {
            id: list
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            model: panel.host.queue
            boundsBehavior: Flickable.StopAtBounds
            ScrollBar.vertical: ScrollBar {}
            currentIndex: panel.selected
            delegate: Rectangle {
                id: row
                required property int index
                required property string modelData
                readonly property bool playing: index === panel.host.queueIndex
                width: list.width
                height: 30
                radius: 4
                color: index === panel.selected ? Theme.menuHover : rowMouse.containsMouse ? Theme.chromeHover : "transparent"
                RowLayout {
                    anchors { fill: parent; leftMargin: 6; rightMargin: 6 }
                    spacing: 6
                    Text { Layout.preferredWidth: 24; text: row.index + 1; color: Theme.textMuted; font.family: Theme.fontFamily; font.pixelSize: 11; horizontalAlignment: Text.AlignRight }
                    Icon { visible: row.playing; name: panel.host.player.paused ? "pause" : "play_arrow"; size: 15; color: Theme.accent }
                    Text {
                        Layout.fillWidth: true
                        text: app.displayName(row.modelData)
                        elide: Text.ElideMiddle
                        color: row.playing ? Theme.accent : Theme.chromeText
                        font.family: Theme.fontFamily; font.pixelSize: 12
                    }
                }
                MouseArea {
                    id: rowMouse
                    anchors.fill: parent
                    hoverEnabled: true
                    acceptedButtons: Qt.LeftButton | Qt.RightButton
                    onClicked: (m) => {
                        panel.selected = row.index
                        panel.forceActiveFocus()
                        if (m.button === Qt.RightButton) { rowMenu.index = row.index; rowMenu.popup() }
                    }
                    onDoubleClicked: panel.host.playAt(row.index)
                }
            }
            Text {
                anchors.centerIn: parent
                visible: panel.host.queue.length === 0
                width: parent.width - 40
                wrapMode: Text.Wrap
                horizontalAlignment: Text.AlignHCenter
                text: "The playlist is empty. Add files, a folder or an .m3u playlist."
                color: Theme.textMuted; font.family: Theme.fontFamily; font.pixelSize: 12
            }
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: 0
            IconButton { iconName: "add"; tip: "Add files"; onClicked: panel.addFilesRequested() }
            IconButton { iconName: "create_new_folder"; tip: "Add a folder"; onClicked: panel.addFolderRequested() }
            IconButton { iconName: "folder_open"; tip: "Open a playlist (.m3u)"; onClicked: panel.loadRequested() }
            IconButton { iconName: "save"; tip: "Save as .m3u"; enabled: panel.host.queue.length > 0; onClicked: panel.saveRequested() }
            Item { Layout.fillWidth: true }
            IconButton { iconName: "arrow_upward"; tip: "Move up"; enabled: panel.selected > 0; onClicked: { panel.host.moveInQueue(panel.selected, panel.selected - 1); panel.selected-- } }
            IconButton { iconName: "arrow_downward"; tip: "Move down"; enabled: panel.selected >= 0 && panel.selected < panel.host.queue.length - 1; onClicked: { panel.host.moveInQueue(panel.selected, panel.selected + 1); panel.selected++ } }
            IconButton { iconName: "delete_outline"; tip: "Remove (Delete)"; enabled: panel.selected >= 0; onClicked: panel.host.removeFromQueue(panel.selected) }
            IconButton { iconName: "clear_all"; tip: "Clear everything except what is playing"; enabled: panel.host.queue.length > 1; onClicked: panel.host.clearQueue() }
        }
    }

    ChaosMenu {
        id: rowMenu
        property int index: -1
        ChaosMenuItem { text: "Play"; onTriggered: panel.host.playAt(rowMenu.index) }
        ChaosMenuItem { text: "Move up"; enabled: rowMenu.index > 0; onTriggered: panel.host.moveInQueue(rowMenu.index, rowMenu.index - 1) }
        ChaosMenuItem { text: "Move down"; enabled: rowMenu.index < panel.host.queue.length - 1; onTriggered: panel.host.moveInQueue(rowMenu.index, rowMenu.index + 1) }
        ChaosMenuItem { text: "Remove"; shortcut: "Delete"; onTriggered: panel.host.removeFromQueue(rowMenu.index) }
    }
}
