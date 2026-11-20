import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// Grid card: thumbnail with duration, NEW and favourite badges and a progress
// bar; name and details below. Folders show their cover and video count.
Item {
    id: card
    required property int index
    required property string path
    required property string name
    required property string folderName
    required property string sizeLabel
    required property string durationLabel
    required property real progress
    required property bool finished
    required property bool favorite
    required property bool isNew
    required property string thumb
    required property bool isFolder
    required property string countLabel
    signal activated()
    signal menuRequested(real x, real y)

    Rectangle {
        id: frame
        anchors { fill: parent; margins: 7 }
        radius: 8
        color: mouse.containsMouse ? Theme.pageHover : "transparent"
        border.color: mouse.containsMouse ? Theme.pageAccent : "transparent"

        ColumnLayout {
            anchors { fill: parent; margins: 5 }
            spacing: 6

            Rectangle {
                id: thumbBox
                Layout.fillWidth: true
                Layout.preferredHeight: width * 9 / 16
                radius: 6
                color: Theme.thumbBg
                clip: true

                Image {
                    id: img
                    anchors.fill: parent
                    source: card.thumb
                    sourceSize: Qt.size(320, 180)
                    asynchronous: true
                    fillMode: Image.PreserveAspectCrop
                    opacity: status === Image.Ready ? 1 : 0
                    Behavior on opacity { NumberAnimation { duration: 180 } }
                }
                Icon {
                    anchors.centerIn: parent
                    visible: img.status !== Image.Ready
                    name: card.isFolder ? "folder" : "movie"
                    size: 34
                    color: Theme.pageTextMuted
                    opacity: 0.6
                }
                // Folder badge
                Rectangle {
                    visible: card.isFolder
                    anchors { left: parent.left; bottom: parent.bottom; margins: 6 }
                    radius: 4; height: 22; width: folderRow.implicitWidth + 12
                    color: "#C0000000"
                    Row { id: folderRow; anchors.centerIn: parent; spacing: 4
                        Icon { name: "folder"; size: 14; color: "white"; anchors.verticalCenter: parent.verticalCenter }
                        Text { text: card.countLabel; color: "white"; font.family: Theme.fontFamily; font.pixelSize: 11; anchors.verticalCenter: parent.verticalCenter }
                    }
                }
                Rectangle {
                    visible: !card.isFolder && card.durationLabel.length > 0
                    anchors { right: parent.right; bottom: parent.bottom; margins: 6; bottomMargin: card.progress > 0 ? 9 : 6 }
                    radius: 3; height: 18; width: durText.implicitWidth + 10
                    color: "#C0000000"
                    Text { id: durText; anchors.centerIn: parent; text: card.durationLabel; color: "white"; font.family: Theme.fontFamily; font.pixelSize: 11 }
                }
                Rectangle {
                    visible: card.isNew && !card.isFolder && card.progress === 0 && !card.finished
                    anchors { left: parent.left; top: parent.top; margins: 6 }
                    radius: 3; height: 18; width: newText.implicitWidth + 10
                    color: Theme.accent2
                    Text { id: newText; anchors.centerIn: parent; text: "NEW"; color: "#1A1205"; font.family: Theme.fontFamily; font.pixelSize: 10; font.weight: Font.Bold }
                }
                Row {
                    anchors { right: parent.right; top: parent.top; margins: 6 }
                    spacing: 4
                    Rectangle {
                        visible: card.finished
                        width: 22; height: 22; radius: 11; color: "#C0000000"
                        Icon { anchors.centerIn: parent; name: "check"; size: 15; color: Theme.success }
                    }
                    Rectangle {
                        visible: card.favorite
                        width: 22; height: 22; radius: 11; color: "#C0000000"
                        Icon { anchors.centerIn: parent; name: "favorite"; size: 14; color: "#FF6B81" }
                    }
                }
                // Hover: play
                Rectangle {
                    anchors.centerIn: parent
                    visible: mouse.containsMouse
                    width: 44; height: 44; radius: 22
                    color: "#A0000000"
                    Icon { anchors.centerIn: parent; name: card.isFolder ? "folder_open" : (card.progress > 0 ? "play_arrow" : "play_arrow"); size: 28; color: "white" }
                }
                // Watch progress
                Rectangle {
                    visible: card.progress > 0 && !card.finished
                    anchors { left: parent.left; right: parent.right; bottom: parent.bottom }
                    height: 3
                    color: "#80000000"
                    Rectangle { width: parent.width * card.progress; height: parent.height; color: Theme.accent }
                }
            }

            Text {
                Layout.fillWidth: true
                text: card.name
                color: Theme.pageText
                font.family: Theme.fontFamily
                font.pixelSize: 13
                font.weight: Font.Medium
                elide: Text.ElideRight
                maximumLineCount: 2
                wrapMode: Text.Wrap
            }
            Text {
                Layout.fillWidth: true
                Layout.topMargin: -3
                text: card.isFolder ? card.sizeLabel : (card.folderName + "  ·  " + card.sizeLabel)
                color: Theme.pageTextMuted
                font.family: Theme.fontFamily
                font.pixelSize: 11
                elide: Text.ElideRight
            }
            Item { Layout.fillHeight: true }
        }
    }

    MouseArea {
        id: mouse
        anchors.fill: parent
        hoverEnabled: true
        acceptedButtons: Qt.LeftButton | Qt.RightButton
        cursorShape: Qt.PointingHandCursor
        onClicked: (m) => {
            if (m.button === Qt.RightButton) card.menuRequested(m.x, m.y)
            else card.activated()
        }
    }
}
