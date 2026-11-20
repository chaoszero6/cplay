import QtQuick
import QtQuick.Controls.Basic

// Text button for the library/network/settings pages: outlined, or filled when [primary]
AbstractButton {
    id: b
    property string iconName: ""
    property bool primary: false
    hoverEnabled: true
    implicitHeight: 34
    implicitWidth: row.implicitWidth + 24
    opacity: enabled ? 1 : 0.45
    Accessible.name: text
    background: Rectangle {
        radius: 6
        color: b.primary ? (b.pressed ? Qt.darker(Theme.pageAccent, 1.15) : b.hovered ? Qt.lighter(Theme.pageAccent, 1.08) : Theme.pageAccent)
                         : (b.pressed ? Theme.pageSelected : b.hovered ? Theme.pageHover : "transparent")
        border.color: b.primary ? "transparent" : (b.hovered ? Theme.pageAccent : Theme.pageBorder)
    }
    contentItem: Item {
        implicitWidth: row.implicitWidth
        Row {
            id: row
            anchors.centerIn: parent
            spacing: 6
            Icon { visible: b.iconName.length > 0; anchors.verticalCenter: parent.verticalCenter; name: b.iconName; size: 17; color: b.primary ? "white" : Theme.pageAccent }
            Text { anchors.verticalCenter: parent.verticalCenter; text: b.text; color: b.primary ? "white" : Theme.pageText; font.family: Theme.fontFamily; font.pixelSize: 13 }
        }
    }
}
