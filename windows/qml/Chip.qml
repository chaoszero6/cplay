import QtQuick
import QtQuick.Controls.Basic

// Filter chip / segmented choice on the pages
AbstractButton {
    id: c
    checkable: true
    hoverEnabled: true
    implicitHeight: 30
    implicitWidth: t.implicitWidth + 26
    Accessible.name: text
    background: Rectangle {
        radius: 15
        color: c.checked ? Theme.pageSelected : c.hovered ? Theme.pageHover : "transparent"
        border.color: c.checked ? Theme.pageAccent : Theme.pageBorder
    }
    contentItem: Text {
        id: t
        text: c.text
        horizontalAlignment: Text.AlignHCenter
        verticalAlignment: Text.AlignVCenter
        color: c.checked ? Theme.pageAccent : Theme.pageText
        font.family: Theme.fontFamily
        font.pixelSize: 13
        font.weight: c.checked ? Font.DemiBold : Font.Normal
    }
}
