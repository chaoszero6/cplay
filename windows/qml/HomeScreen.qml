import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// What the window shows when nothing plays: a sidebar (Library, Network,
// Settings) and the chosen page. Follows the Appearance setting (dark/light).
Rectangle {
    id: home
    required property var host            // PlayerPage
    property string section: app.setting("home/section", "library")
    signal openFiles()
    signal openLink()
    signal customSizeRequested()
    property alias networkPage: networkPage
    property alias libraryPage: libraryPage

    color: Theme.pageBg
    onSectionChanged: app.setSetting("home/section", section)

    // Keep clicks and the wheel away from the video area underneath
    MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons; onWheel: (w) => w.accepted = true }

    readonly property bool compactSidebar: width < 820

    component NavItem: AbstractButton {
        id: nav
        property string iconName
        property string key
        readonly property bool current: home.section === key
        Layout.fillWidth: true
        implicitHeight: 40
        hoverEnabled: true
        Accessible.name: text
        ToolTip.visible: home.compactSidebar && hovered
        ToolTip.text: text
        ToolTip.delay: 400
        background: Rectangle {
            radius: 6
            color: nav.current ? Theme.pageSelected : nav.hovered ? Theme.pageHover : "transparent"
            Rectangle { visible: nav.current; width: 3; height: 18; radius: 1.5; anchors.verticalCenter: parent.verticalCenter; color: Theme.pageAccent }
        }
        contentItem: RowLayout {
            spacing: 12
            Item { Layout.preferredWidth: home.compactSidebar ? 0 : 2 }
            Icon { name: nav.iconName; size: 20; color: nav.current ? Theme.pageAccent : Theme.pageTextMuted; Layout.alignment: home.compactSidebar ? Qt.AlignHCenter : Qt.AlignLeft; Layout.fillWidth: home.compactSidebar }
            Text { visible: !home.compactSidebar; Layout.fillWidth: true; text: nav.text; color: Theme.pageText; font.family: Theme.fontFamily; font.pixelSize: 14; font.weight: nav.current ? Font.DemiBold : Font.Normal }
        }
        onClicked: home.section = key
    }

    RowLayout {
        anchors.fill: parent
        spacing: 0

        // ---- sidebar ----
        Rectangle {
            Layout.fillHeight: true
            Layout.preferredWidth: home.compactSidebar ? 60 : 196
            color: Theme.sidebarBg
            ColumnLayout {
                anchors { fill: parent; margins: 8; topMargin: 12 }
                spacing: 4
                NavItem { text: "Library"; iconName: "video_library"; key: "library" }
                NavItem { text: "Network"; iconName: "lan"; key: "network" }
                NavItem { text: "Settings"; iconName: "settings"; key: "settings" }
                Item { Layout.fillHeight: true }
                NavItem { text: "Open file"; iconName: "folder_open"; key: "-"; onClicked: { home.section = home.section; home.openFiles() } }
                NavItem { text: "Open link"; iconName: "link"; key: "-"; onClicked: { home.section = home.section; home.openLink() } }
                Text {
                    visible: !home.compactSidebar
                    Layout.fillWidth: true
                    Layout.topMargin: 6
                    Layout.leftMargin: 8
                    text: "CPlay " + app.version + "  ·  no ads"
                    color: Theme.pageTextMuted
                    font.family: Theme.fontFamily
                    font.pixelSize: 11
                }
            }
            Rectangle { anchors { right: parent.right; top: parent.top; bottom: parent.bottom } width: 1; color: Theme.pageBorder }
        }

        // ---- pages ----
        StackLayout {
            Layout.fillWidth: true
            Layout.fillHeight: true
            currentIndex: home.section === "network" ? 1 : home.section === "settings" ? 2 : 0
            LibraryPage { id: libraryPage; host: home.host; onOpenFiles: home.openFiles() }
            NetworkPage { id: networkPage; host: home.host }
            SettingsPage { host: home.host; onCustomSizeRequested: home.customSizeRequested() }
        }
    }
}
