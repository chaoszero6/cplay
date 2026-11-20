import QtQuick
import QtQuick.Controls.Basic

// On/off toggle for the settings page
AbstractButton {
    id: s
    checkable: true
    hoverEnabled: true
    implicitWidth: 40
    implicitHeight: 22
    Accessible.role: Accessible.CheckBox
    background: Rectangle {
        radius: 11
        color: s.checked ? Theme.pageAccent : Theme.pageSurface2
        border.color: s.checked ? Theme.pageAccent : Theme.pageBorder
        Rectangle {
            width: 16; height: 16; radius: 8
            y: 3
            x: s.checked ? parent.width - width - 3 : 3
            color: s.checked ? "white" : Theme.pageTextMuted
            Behavior on x { NumberAnimation { duration: 120 } }
        }
    }
}
