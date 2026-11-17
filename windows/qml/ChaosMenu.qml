import QtQuick
import QtQuick.Controls.Basic

// Dark compact menu used for the main menu and the right-click menu.
Menu {
    id: menu
    delegate: ChaosMenuItem {}
    topPadding: 4
    bottomPadding: 4
    implicitWidth: 260
    background: Rectangle {
        implicitWidth: 260
        color: Theme.menuBg
        border.color: Theme.chromeBorder
        radius: 6
    }
}
