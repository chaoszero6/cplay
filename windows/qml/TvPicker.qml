import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// "Play on TV": TVs and Chromecasts on the home network (or added by address).
Popup {
    id: picker
    signal chosen(string deviceId)

    modal: true
    focus: true
    anchors.centerIn: Overlay.overlay
    width: Math.min(460, (parent ? parent.width : 500) - 40)
    padding: 20
    background: Rectangle { radius: 10; color: Theme.menuBg; border.color: Theme.chromeBorder }
    Overlay.modal: Rectangle { color: "#99000000" }
    onOpened: { addError.text = ""; tv.discover() }

    Connections {
        target: tv
        function onDeviceAdded(ok, message) {
            if (!picker.opened) return
            addError.text = ok ? "" : message
            if (ok) addField.text = ""
        }
    }

    contentItem: ColumnLayout {
        spacing: 10
        RowLayout {
            spacing: 8
            Icon { name: "cast"; size: 22; color: Theme.accent }
            Text { Layout.fillWidth: true; text: "Play on TV"; color: Theme.text; font.family: Theme.fontFamily; font.pixelSize: 17; font.weight: Font.DemiBold }
            BusyIndicator { visible: tv.searching; running: visible; Layout.preferredWidth: 22; Layout.preferredHeight: 22 }
            IconButton { iconName: "refresh"; iconSize: 18; tip: "Search again"; enabled: !tv.searching; onClicked: tv.discover() }
        }
        Text {
            Layout.fillWidth: true; wrapMode: Text.Wrap
            text: tv.searching && tv.devices.length === 0 ? "Looking for smart TVs and Chromecasts on your network…"
                  : tv.devices.length === 0 ? "No TV answered. Check that the TV is on and on the same network, or add it by address below." : "Choose a TV:"
            color: Theme.textMuted; font.family: Theme.fontFamily; font.pixelSize: 13
        }
        Repeater {
            model: tv.devices
            Rectangle {
                id: dev
                required property var modelData
                Layout.fillWidth: true
                height: 52
                radius: 6
                color: devMouse.containsMouse ? Theme.menuHover : Theme.surface2
                RowLayout {
                    anchors { fill: parent; leftMargin: 12; rightMargin: 12 }
                    spacing: 12
                    Icon { name: dev.modelData.kind === "chromecast" ? "cast" : "tv"; size: 22; color: Theme.accent }
                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 0
                        Text { Layout.fillWidth: true; text: dev.modelData.name; elide: Text.ElideRight; color: Theme.text; font.family: Theme.fontFamily; font.pixelSize: 14 }
                        Text { text: dev.modelData.detail + "  ·  " + dev.modelData.host; color: Theme.textMuted; font.family: Theme.fontFamily; font.pixelSize: 11 }
                    }
                }
                MouseArea { id: devMouse; anchors.fill: parent; hoverEnabled: true; cursorShape: Qt.PointingHandCursor; onClicked: { picker.close(); picker.chosen(dev.modelData.id) } }
            }
        }
        Text { Layout.topMargin: 6; text: "Add by address"; color: Theme.textMuted; font.family: Theme.fontFamily; font.pixelSize: 12 }
        RowLayout {
            Layout.fillWidth: true
            spacing: 8
            TextField { id: addField; Layout.fillWidth: true; placeholderText: "Chromecast IP (192.168.1.20) or DLNA description URL"; onAccepted: tv.addDevice(text) }
            Button { text: "Add"; enabled: addField.text.trim().length > 0; onClicked: tv.addDevice(addField.text) }
        }
        Text { id: addError; visible: text.length > 0; Layout.fillWidth: true; wrapMode: Text.Wrap; color: Theme.danger; font.family: Theme.fontFamily; font.pixelSize: 12 }
        Text {
            Layout.fillWidth: true; wrapMode: Text.Wrap
            text: "The TV fetches the video from this PC. Windows may ask once to let CPlay use your home network: allow private networks."
            color: Theme.textMuted; font.family: Theme.fontFamily; font.pixelSize: 11
        }
    }
}
