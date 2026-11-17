import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// Custom title bar: drag to move (with magnetic screen edges), double-click to
// maximize; logo opens the main menu; pin keeps the window on top.
Rectangle {
    id: bar
    property string title: "CPlay"
    property bool fullscreen: false
    signal menuRequested(Item anchor)
    signal toggleOnTop()
    signal closeRequested()

    height: Theme.titleBarHeight
    clip: true
    gradient: Gradient {
        GradientStop { position: 0; color: Theme.chromeTop }
        GradientStop { position: 1; color: Theme.chromeBottom }
    }
    Rectangle { anchors { left: parent.left; right: parent.right; bottom: parent.bottom } height: 1; color: Theme.chromeBorder }

    // Drag area under the buttons
    MouseArea {
        anchors.fill: parent
        acceptedButtons: Qt.LeftButton
        onPressed: windowCtl.startDrag()
        onPositionChanged: windowCtl.dragMove()
        onReleased: windowCtl.endDrag()
        onCanceled: windowCtl.endDrag()
        onDoubleClicked: windowCtl.toggleMaximize()
    }

    RowLayout {
        anchors { fill: parent; leftMargin: 4; rightMargin: 0 }
        spacing: 2

        // Logo = main menu, like classic players
        AbstractButton {
            id: logo
            Layout.preferredHeight: 28
            Layout.preferredWidth: logoRow.implicitWidth + 16
            hoverEnabled: true
            Accessible.name: "CPlay menu"
            background: Rectangle { radius: 4; color: logo.hovered || logo.pressed ? Theme.chromeHover : "transparent" }
            contentItem: Row {
                id: logoRow
                spacing: 6
                leftPadding: 8
                Image { anchors.verticalCenter: parent.verticalCenter; source: "qrc:/qt/qml/ChaosPlay/assets/chaosplay-256.png"; sourceSize: Qt.size(18, 18); width: 18; height: 18 }
                // Narrow window: just the logo, so the video title keeps some room
                Text { visible: bar.width >= 600; anchors.verticalCenter: parent.verticalCenter; text: "CPlay"; color: Theme.accent; font.family: Theme.fontFamily; font.pixelSize: 13; font.weight: Font.Bold }
                Icon { anchors.verticalCenter: parent.verticalCenter; name: "arrow_drop_down"; size: 18; color: Theme.chromeIcon }
            }
            onClicked: bar.menuRequested(logo)
        }

        Text {
            Layout.fillWidth: true
            Layout.minimumWidth: 0
            Layout.leftMargin: 8
            text: bar.title
            color: Theme.chromeText
            elide: Text.ElideMiddle
            font.family: Theme.fontFamily
            font.pixelSize: 13
        }

        IconButton {
            iconName: "push_pin"
            iconSize: 17
            checked: app.alwaysOnTop
            tip: app.alwaysOnTop ? "Always on top: on (Ctrl+T)" : "Always on top (Ctrl+T)"
            onClicked: bar.toggleOnTop()
        }
        IconButton { iconName: "remove"; iconSize: 18; implicitWidth: 44; tip: "Minimize"; onClicked: windowCtl.minimize() }
        IconButton {
            iconName: windowCtl.maximized ? "filter_none" : "crop_square"
            iconSize: windowCtl.maximized ? 15 : 17
            implicitWidth: 44
            visible: !bar.fullscreen
            tip: windowCtl.maximized ? "Restore" : "Maximize"
            onClicked: windowCtl.toggleMaximize()
        }
        IconButton { iconName: "close"; iconSize: 19; implicitWidth: 46; hoverColor: Theme.closeHover; tip: "Close"; onClicked: bar.closeRequested() }
    }
}
