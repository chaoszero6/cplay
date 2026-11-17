import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// What the video area shows when nothing plays: logo, open buttons, recent files.
Item {
    id: idle
    signal openFiles()
    signal openLink()
    signal playLocation(string location)

    Rectangle {
        anchors.fill: parent
        gradient: Gradient {
            GradientStop { position: 0; color: "#101318" }
            GradientStop { position: 1; color: "#06070A" }
        }
    }

    component OutlineButton: AbstractButton {
        id: ob
        property string iconName
        hoverEnabled: true
        implicitHeight: 36
        implicitWidth: obRow.implicitWidth + 28
        background: Rectangle {
            radius: 4
            color: ob.hovered ? Theme.chromeHover : "transparent"
            border.color: ob.hovered ? Theme.accent : Theme.chromeBorder
        }
        contentItem: Row {
            id: obRow
            spacing: 8
            leftPadding: 14
            Icon { anchors.verticalCenter: parent.verticalCenter; name: ob.iconName; size: 18; color: Theme.accent }
            Text { anchors.verticalCenter: parent.verticalCenter; text: ob.text; color: Theme.chromeText; font.family: Theme.fontFamily; font.pixelSize: 13 }
        }
    }

    ColumnLayout {
        anchors.centerIn: parent
        width: Math.min(parent.width - 40, 560)
        spacing: 14

        Image {
            Layout.alignment: Qt.AlignHCenter
            source: "qrc:/qt/qml/ChaosPlay/assets/chaosplay-256.png"
            sourceSize: Qt.size(84, 84)
            Layout.preferredWidth: 84
            Layout.preferredHeight: 84
            opacity: 0.95
        }
        Text {
            Layout.alignment: Qt.AlignHCenter
            text: "CPlay"
            color: Theme.text
            font.family: Theme.fontFamily
            font.pixelSize: 26
            font.weight: Font.DemiBold
            font.letterSpacing: 1
        }
        Text {
            Layout.alignment: Qt.AlignHCenter
            text: "Drop a video here, or open one"
            color: Theme.textMuted
            font.family: Theme.fontFamily
            font.pixelSize: 13
        }
        Row {
            Layout.alignment: Qt.AlignHCenter
            spacing: 10
            OutlineButton { text: "Open file"; iconName: "folder_open"; onClicked: idle.openFiles() }
            OutlineButton { text: "Open link"; iconName: "link"; onClicked: idle.openLink() }
        }

        Text {
            Layout.topMargin: 14
            visible: app.recent.length > 0
            text: "RECENT"
            color: Theme.textMuted
            font.family: Theme.fontFamily
            font.pixelSize: 11
            font.letterSpacing: 1.5
            font.weight: Font.DemiBold
        }
        Rectangle {
            Layout.fillWidth: true
            visible: app.recent.length > 0
            Layout.preferredHeight: recentColumn.implicitHeight + 8
            color: "#0DFFFFFF"
            radius: 6
            border.color: Theme.chromeBorder
            Column {
                id: recentColumn
                anchors { left: parent.left; right: parent.right; top: parent.top; margins: 4 }
                Repeater {
                    model: app.recent.slice(0, 6)
                    delegate: Rectangle {
                        id: row
                        required property var modelData
                        width: recentColumn.width
                        height: 34
                        radius: 4
                        color: rowMouse.containsMouse ? Theme.menuHover : "transparent"
                        MouseArea {
                            id: rowMouse
                            anchors.fill: parent
                            hoverEnabled: true
                            cursorShape: Qt.PointingHandCursor
                            onClicked: idle.playLocation(row.modelData.location)
                        }
                        RowLayout {
                            anchors { fill: parent; leftMargin: 10; rightMargin: 2 }
                            spacing: 10
                            Icon { name: row.modelData.finished ? "check_circle" : "play_circle_outline"; size: 18; color: row.modelData.finished ? Theme.success : Theme.accent }
                            Text { Layout.fillWidth: true; text: row.modelData.title; color: Theme.chromeText; elide: Text.ElideRight; font.family: Theme.fontFamily; font.pixelSize: 13 }
                            Rectangle {
                                Layout.preferredWidth: 60; height: 3; radius: 1.5; color: Theme.surface3
                                Rectangle { width: parent.width * row.modelData.progress; height: 3; radius: 1.5; color: row.modelData.finished ? Theme.success : Theme.accent }
                            }
                            Text { Layout.preferredWidth: 78; text: row.modelData.remaining; color: Theme.textMuted; font.family: Theme.fontFamily; font.pixelSize: 11 }
                            IconButton { iconName: "close"; iconSize: 15; implicitWidth: 26; implicitHeight: 26; tip: "Remove from recent"; onClicked: app.forget(row.modelData.location) }
                        }
                    }
                }
            }
        }
    }
}
