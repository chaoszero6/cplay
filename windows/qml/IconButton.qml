import QtQuick
import QtQuick.Controls.Basic

// Flat skin button: grey icon, lighter on hover, accent when checked.
AbstractButton {
    id: control
    property string iconName
    property string tip
    property int iconSize: 22
    property color iconColor: Theme.chromeIcon
    property color hoverColor: Theme.chromeHover
    property string label: ""   // short text instead of an icon ("1.5x")

    implicitWidth: label.length > 0 ? Math.max(40, labelText.implicitWidth + 16) : 34
    implicitHeight: 32
    focusPolicy: Qt.NoFocus
    hoverEnabled: true
    Accessible.name: tip
    ToolTip.visible: hovered && tip.length > 0
    ToolTip.delay: 700
    ToolTip.text: tip

    background: Rectangle {
        radius: 4
        color: control.pressed ? Qt.lighter(control.hoverColor, 1.25)
             : control.hovered ? control.hoverColor : "transparent"
        Behavior on color { ColorAnimation { duration: 90 } }
    }
    contentItem: Item {
        Icon {
            anchors.centerIn: parent
            visible: control.label.length === 0
            name: control.iconName
            size: control.iconSize
            color: !control.enabled ? Qt.rgba(1, 1, 1, 0.25)
                 : control.checked ? Theme.accent
                 : control.hovered ? "white" : control.iconColor
        }
        Text {
            id: labelText
            anchors.centerIn: parent
            visible: control.label.length > 0
            text: control.label
            color: control.checked ? Theme.accent : control.hovered ? "white" : control.iconColor
            font.family: Theme.fontFamily
            font.pixelSize: 13
            font.weight: Font.DemiBold
        }
    }
}
