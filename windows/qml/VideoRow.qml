import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// List row: small thumbnail, name, folder, duration, size, date.
Rectangle {
    id: row
    required property int index
    required property string path
    required property string name
    required property string folderName
    required property string sizeLabel
    required property string dateLabel
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

    height: 58
    radius: 6
    color: mouse.containsMouse ? Theme.pageHover : (index % 2 === 1 ? Qt.rgba(0.5, 0.5, 0.5, 0.04) : "transparent")

    RowLayout {
        anchors { fill: parent; leftMargin: 8; rightMargin: 12 }
        spacing: 12
        Rectangle {
            Layout.preferredWidth: 76
            Layout.preferredHeight: 43
            radius: 4
            color: Theme.thumbBg
            clip: true
            Image { id: img; anchors.fill: parent; source: row.thumb; sourceSize: Qt.size(160, 90); asynchronous: true; fillMode: Image.PreserveAspectCrop }
            Icon { anchors.centerIn: parent; visible: img.status !== Image.Ready; name: row.isFolder ? "folder" : "movie"; size: 20; color: Theme.pageTextMuted }
            Rectangle {
                visible: row.progress > 0 && !row.finished
                anchors { left: parent.left; right: parent.right; bottom: parent.bottom }
                height: 3; color: "#80000000"
                Rectangle { width: parent.width * row.progress; height: parent.height; color: Theme.accent }
            }
        }
        ColumnLayout {
            Layout.fillWidth: true
            spacing: 2
            RowLayout {
                spacing: 6
                Layout.fillWidth: true
                Text { Layout.fillWidth: true; text: row.name; color: Theme.pageText; font.family: Theme.fontFamily; font.pixelSize: 13; font.weight: Font.Medium; elide: Text.ElideRight }
                Rectangle { visible: row.isNew && !row.isFolder && row.progress === 0 && !row.finished; radius: 3; height: 16; width: nt.implicitWidth + 8; color: Theme.accent2
                    Text { id: nt; anchors.centerIn: parent; text: "NEW"; color: "#1A1205"; font.pixelSize: 9; font.weight: Font.Bold; font.family: Theme.fontFamily } }
                Icon { visible: row.finished; name: "check_circle"; size: 15; color: Theme.success }
                Icon { visible: row.favorite; name: "favorite"; size: 14; color: "#FF6B81" }
            }
            Text { Layout.fillWidth: true; text: row.isFolder ? row.countLabel : row.folderName; color: Theme.pageTextMuted; font.family: Theme.fontFamily; font.pixelSize: 11; elide: Text.ElideMiddle }
        }
        Text { visible: row.width > 560 && !row.isFolder; Layout.preferredWidth: 64; horizontalAlignment: Text.AlignRight; text: row.durationLabel; color: Theme.pageTextMuted; font.family: Theme.fontFamily; font.pixelSize: 12 }
        Text { visible: row.width > 680; Layout.preferredWidth: 74; horizontalAlignment: Text.AlignRight; text: row.sizeLabel; color: Theme.pageTextMuted; font.family: Theme.fontFamily; font.pixelSize: 12 }
        Text { visible: row.width > 800; Layout.preferredWidth: 96; horizontalAlignment: Text.AlignRight; text: row.dateLabel; color: Theme.pageTextMuted; font.family: Theme.fontFamily; font.pixelSize: 12 }
    }

    MouseArea {
        id: mouse
        anchors.fill: parent
        hoverEnabled: true
        acceptedButtons: Qt.LeftButton | Qt.RightButton
        cursorShape: Qt.PointingHandCursor
        onClicked: (m) => {
            if (m.button === Qt.RightButton) row.menuRequested(m.x, m.y)
            else row.activated()
        }
    }
}
