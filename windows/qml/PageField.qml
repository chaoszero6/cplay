import QtQuick
import QtQuick.Controls.Basic

// Text field in the page colours
TextField {
    id: f
    implicitHeight: 34
    color: Theme.pageText
    placeholderTextColor: Theme.pageTextMuted
    selectionColor: Theme.pageAccent
    selectedTextColor: "white"
    font.family: Theme.fontFamily
    font.pixelSize: 13
    leftPadding: 10
    rightPadding: 10
    background: Rectangle {
        radius: 6
        color: Theme.pageSurface
        border.color: f.activeFocus ? Theme.pageAccent : Theme.pageBorder
        border.width: f.activeFocus ? 1.5 : 1
    }
}
