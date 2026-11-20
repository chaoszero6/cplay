import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Dialogs
import QtQuick.Layouts

// Settings: library folders, playback, window, subtitles, appearance, about.
Item {
    id: settings
    required property var host            // PlayerPage
    signal customSizeRequested()

    component Section: ColumnLayout {
        property string title
        default property alias content: body.data
        Layout.fillWidth: true
        spacing: 0
        Text { text: parent.title; color: Theme.pageAccent; font.family: Theme.fontFamily; font.pixelSize: 12; font.weight: Font.Bold; font.letterSpacing: 1; Layout.bottomMargin: 8; Layout.topMargin: 18 }
        Rectangle {
            Layout.fillWidth: true
            implicitHeight: body.implicitHeight + 8
            radius: 10
            color: Theme.pageSurface
            border.color: Theme.pageBorder
            ColumnLayout { id: body; anchors { left: parent.left; right: parent.right; top: parent.top; margins: 4 } spacing: 0 }
        }
    }

    component Row_: RowLayout {
        id: r
        property string label
        property string hint: ""
        default property alias control: holder.data
        Layout.fillWidth: true
        Layout.leftMargin: 14
        Layout.rightMargin: 14
        Layout.topMargin: 10
        Layout.bottomMargin: 10
        spacing: 16
        ColumnLayout {
            Layout.fillWidth: true
            spacing: 2
            Text { Layout.fillWidth: true; text: r.label; color: Theme.pageText; font.family: Theme.fontFamily; font.pixelSize: 14; wrapMode: Text.Wrap }
            Text { Layout.fillWidth: true; visible: r.hint.length > 0; text: r.hint; color: Theme.pageTextMuted; font.family: Theme.fontFamily; font.pixelSize: 12; wrapMode: Text.Wrap }
        }
        Row { id: holder; spacing: 6; Layout.alignment: Qt.AlignVCenter | Qt.AlignRight }
    }

    component Divider: Rectangle { Layout.fillWidth: true; Layout.leftMargin: 14; Layout.rightMargin: 14; height: 1; color: Theme.pageBorder }

    Flickable {
        id: flick
        anchors.fill: parent
        contentHeight: column.implicitHeight + 40
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

        ColumnLayout {
            id: column
            x: 24
            y: 12
            width: Math.min(flick.width - 48, 760)
            spacing: 0

            Text { text: "Settings"; color: Theme.pageText; font.family: Theme.fontFamily; font.pixelSize: Theme.headerTitleSize; font.weight: Font.DemiBold; Layout.topMargin: 8 }

            // ---- library ----
            Section {
                title: "LIBRARY"
                Repeater {
                    model: library.folders
                    RowLayout {
                        required property string modelData
                        Layout.fillWidth: true
                        Layout.leftMargin: 14
                        Layout.rightMargin: 6
                        Layout.topMargin: 4
                        Layout.bottomMargin: 4
                        spacing: 10
                        Icon { name: "folder"; size: 20; color: Theme.pageAccent }
                        Text { Layout.fillWidth: true; text: modelData.replace(/\//g, "\\"); color: Theme.pageText; elide: Text.ElideMiddle; font.family: Theme.fontFamily; font.pixelSize: 13 }
                        PageIconButton { iconName: "close"; iconSize: 17; tip: "Stop watching this folder (files are not touched)"; onClicked: library.removeFolder(modelData) }
                    }
                }
                Text {
                    visible: library.folders.length === 0
                    Layout.leftMargin: 14; Layout.topMargin: 10
                    text: "No folders yet."
                    color: Theme.pageTextMuted; font.family: Theme.fontFamily; font.pixelSize: 13
                }
                RowLayout {
                    Layout.margins: 10
                    spacing: 8
                    PageButton { text: "Add a folder"; iconName: "create_new_folder"; onClicked: folderDialog.open() }
                    PageButton { text: "Look for new videos"; iconName: "refresh"; onClicked: library.rescan() }
                    PageButton { text: "Rebuild thumbnails"; iconName: "image"; onClicked: { library.clearThumbnails(); settings.host.osdMessage("Thumbnails are being rebuilt") } }
                }
            }

            // ---- playback ----
            Section {
                title: "PLAYBACK"
                Row_ {
                    label: "Resume where I left off"
                    hint: "Videos continue from the last position (Home key starts over)."
                    PageSwitch { checked: app.setting("player/resume", true); onToggled: app.setSetting("player/resume", checked) }
                }
                Divider {}
                Row_ {
                    label: "Queue the rest of the folder"
                    hint: "Opening one video plays the next ones of its folder after it."
                    PageSwitch { checked: settings.host.autoQueueFolder; onToggled: { settings.host.autoQueueFolder = checked; app.setSetting("player/autoQueueFolder", checked) } }
                }
                Divider {}
                Row_ {
                    label: "Seek bar preview"
                    hint: "A picture of the moment under the mouse on the seek bar."
                    PageSwitch { checked: settings.host.previewEnabled; onToggled: { settings.host.previewEnabled = checked; app.setSetting("player/seekPreview", checked) } }
                }
                Divider {}
                Row_ {
                    label: "Hardware decoding"
                    hint: "Uses the graphics card. Turn off if a video shows a black picture with sound."
                    PageSwitch {
                        checked: app.hardwareDecoding
                        onToggled: { app.hardwareDecoding = checked; settings.host.player.setMpvProperty("hwdec", checked ? "auto-safe" : "no") }
                    }
                }
                Divider {}
                Row_ {
                    label: "Volume levelling"
                    hint: "Evens out quiet dialogue and loud scenes."
                    PageSwitch { checked: settings.host.levelling; onToggled: settings.host.setLevelling(checked) }
                }
            }

            // ---- window ----
            Section {
                title: "WINDOW"
                Row_ {
                    label: "Window size when a video starts"
                    hint: windowCtl.sizeMode === "custom" ? "Always " + (windowCtl.customSizeText.length > 0 ? windowCtl.customSizeText : "your size")
                         : windowCtl.sizeMode === "fit" ? "Fit each video (" + Math.round(windowCtl.fitScale * 100) + "%)" : "Keep the size you gave the window"
                    Chip { text: "Keep"; checked: windowCtl.sizeMode === "keep"; onClicked: windowCtl.sizeMode = "keep" }
                    Chip { text: "My size"; checked: windowCtl.sizeMode === "custom"; onClicked: settings.customSizeRequested() }
                    Chip { text: "Fit video"; checked: windowCtl.sizeMode === "fit"; onClicked: windowCtl.sizeMode = "fit" }
                }
                Divider {}
                Row_ {
                    label: "Magnetic screen edges"
                    hint: "The window snaps to edges and corners when dragged close."
                    PageSwitch { checked: windowCtl.magnetic; onToggled: windowCtl.magnetic = checked }
                }
                Divider {}
                Row_ {
                    label: "Always on top"
                    PageSwitch { checked: app.alwaysOnTop; onToggled: app.alwaysOnTop = checked }
                }
                Divider {}
                Row_ {
                    label: "Play every video in this window"
                    hint: "Opening a video from Explorer uses the CPlay window you already have (from the next start)."
                    PageSwitch { checked: app.setting("player/singleInstance", true); onToggled: app.setSetting("player/singleInstance", checked) }
                }
            }

            // ---- subtitles ----
            Section {
                title: "SUBTITLES"
                Row_ {
                    label: "Size"
                    Repeater {
                        model: [[0.75, "Small"], [1.0, "Normal"], [1.3, "Large"], [1.6, "Huge"]]
                        Chip { required property var modelData; text: modelData[1]; checked: Math.abs(settings.host.subScale - modelData[0]) < 0.05; onClicked: settings.host.setSubScale(modelData[0]) }
                    }
                }
                Divider {}
                Row_ {
                    label: "Text encoding"
                    hint: "For subtitles that show wrong letters. More choices in the Control box (Ctrl+G)."
                    Repeater {
                        model: [["auto", "Auto"], ["utf8", "UTF-8"], ["cp1252", "Western"], ["cp1251", "Cyrillic"], ["cp1256", "Arabic"]]
                        Chip { required property var modelData; text: modelData[1]; checked: settings.host.subCodepage === modelData[0]; onClicked: settings.host.setSubCodepage(modelData[0]) }
                    }
                }
            }

            // ---- appearance ----
            Section {
                title: "APPEARANCE"
                Row_ {
                    label: "Library and settings colours"
                    hint: "The player itself always stays dark."
                    Chip { text: "Dark"; checked: !Theme.lightPages; onClicked: { Theme.lightPages = false; app.setSetting("app/theme", "dark") } }
                    Chip { text: "Light"; checked: Theme.lightPages; onClicked: { Theme.lightPages = true; app.setSetting("app/theme", "light") } }
                }
            }

            // ---- about ----
            Section {
                title: "ABOUT"
                ColumnLayout {
                    Layout.fillWidth: true
                    Layout.margins: 14
                    spacing: 6
                    RowLayout {
                        spacing: 12
                        Image { source: "qrc:/qt/qml/ChaosPlay/assets/chaosplay-256.png"; sourceSize: Qt.size(40, 40); Layout.preferredWidth: 40; Layout.preferredHeight: 40 }
                        ColumnLayout {
                            spacing: 0
                            Text { text: "CPlay " + app.version; color: Theme.pageText; font.family: Theme.fontFamily; font.pixelSize: 16; font.weight: Font.DemiBold }
                            Text { text: "Every video. Every format. Zero ads."; color: Theme.pageTextMuted; font.family: Theme.fontFamily; font.pixelSize: 12 }
                        }
                    }
                    Text {
                        Layout.fillWidth: true; wrapMode: Text.Wrap
                        text: "Free and open-source software under the GNU General Public License v3.0. No ads, no tracking, no account. Playback by libmpv and FFmpeg; interface by Qt."
                        color: Theme.pageText; font.family: Theme.fontFamily; font.pixelSize: 13
                    }
                    Text {
                        text: "<a href=\"https://github.com/chaoszero6/cplay\">Source code: github.com/chaoszero6/cplay</a>"
                        textFormat: Text.RichText
                        linkColor: Theme.pageAccent
                        color: Theme.pageText; font.family: Theme.fontFamily; font.pixelSize: 13
                        onLinkActivated: (link) => Qt.openUrlExternally(link)
                        MouseArea { anchors.fill: parent; acceptedButtons: Qt.NoButton; cursorShape: parent.hoveredLink ? Qt.PointingHandCursor : Qt.ArrowCursor }
                    }
                }
            }
        }
    }

    FolderDialog {
        id: folderDialog
        title: "Add a folder to the library"
        onAccepted: if (!library.addFolder(selectedFolder)) settings.host.osdMessage("That folder is already in the library")
    }
}
