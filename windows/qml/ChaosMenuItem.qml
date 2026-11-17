import QtQuick
import QtQuick.Controls.Basic

// Menu row: check mark, text, right-aligned shortcut, submenu arrow.
MenuItem {
    id: item
    property string shortcut: ""
    implicitHeight: 30
    implicitWidth: 260
    leftPadding: 30
    rightPadding: 16

    indicator: Icon {
        x: 8
        anchors.verticalCenter: parent.verticalCenter
        visible: item.checkable && item.checked
        name: "check"
        size: 16
        color: Theme.accent
    }
    arrow: Icon {
        x: item.width - width - 6
        anchors.verticalCenter: parent.verticalCenter
        visible: item.subMenu !== null
        name: "chevron_right"
        size: 18
        color: Theme.chromeIcon
    }
    contentItem: Item {
        Text {
            anchors { left: parent.left; verticalCenter: parent.verticalCenter }
            text: item.text
            color: item.enabled ? (item.highlighted ? "white" : Theme.chromeText) : Qt.rgba(1, 1, 1, 0.3)
            font.family: Theme.fontFamily
            font.pixelSize: 13
        }
        Text {
            anchors { right: parent.right; verticalCenter: parent.verticalCenter }
            text: item.shortcut
            color: Theme.textMuted
            font.family: Theme.fontFamily
            font.pixelSize: 12
        }
    }
    background: Rectangle {
        anchors { fill: parent; leftMargin: 4; rightMargin: 4 }
        radius: 4
        color: item.highlighted ? Theme.menuHover : "transparent"
    }
}
