import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Dialogs
import QtQuick.Layouts

// The local video library: tabs (All videos, Folders, Recent, Favorites),
// search, sort, grid or list, continue watching, and a right-click menu.
Item {
    id: lib
    required property var host            // PlayerPage
    signal openFiles()

    readonly property bool inFolder: library.openFolder.length > 0
    readonly property bool showContinue: library.tab === "all" && !inFolder && library.query.length === 0
                                         && library.continueWatching.length > 0
    readonly property bool onboarding: !app.setting("app/onboarded", false) && !welcomeDismissed
    property bool welcomeDismissed: false

    function play(path) { host.playLocations([path]) }
    function activate(isFolder, path) {
        if (isFolder) library.openFolder = path
        else play(path)
    }
    function openMenu(item, isFolder, path, name, x, y) {
        const menu = isFolder ? folderMenu : videoMenu
        menu.path = path
        menu.name = name
        menu.parent = item
        menu.x = Math.min(x, item.width - 10)
        menu.y = y
        menu.open()
    }

    // ---- header ----
    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        RowLayout {
            Layout.fillWidth: true
            Layout.leftMargin: 20
            Layout.rightMargin: 14
            Layout.topMargin: 12
            Layout.preferredHeight: 44
            spacing: 6

            PageIconButton { visible: lib.inFolder; iconName: "arrow_back"; tip: "Back to folders"; onClicked: library.openFolder = "" }
            Text {
                Layout.fillWidth: true
                Layout.minimumWidth: 80
                text: lib.inFolder ? library.folderLabel(library.openFolder) : "Library"
                color: Theme.pageText
                font.family: Theme.fontFamily
                font.pixelSize: Theme.headerTitleSize
                font.weight: Font.DemiBold
                elide: Text.ElideRight
            }
            PageField {
                id: search
                Layout.preferredWidth: Math.min(260, Math.max(140, lib.width * 0.28))
                placeholderText: "Search videos"
                text: library.query
                onTextChanged: searchDelay.restart()
                Keys.onEscapePressed: { text = ""; focus = false }
                rightPadding: 30
                Icon { anchors { right: parent.right; rightMargin: 8; verticalCenter: parent.verticalCenter } name: search.text.length > 0 ? "close" : "search"; size: 17; color: Theme.pageTextMuted
                    MouseArea { anchors.fill: parent; anchors.margins: -4; enabled: search.text.length > 0; cursorShape: Qt.PointingHandCursor; onClicked: search.text = "" } }
                Timer { id: searchDelay; interval: 180; onTriggered: library.query = search.text }
            }
            PageIconButton { id: sortButton; iconName: "sort"; tip: "Sort: " + (library.sortOptions.find(o => o.key === library.sort) || {label: ""}).label; onClicked: sortMenu.popup(sortButton, 0, sortButton.height) }
            PageIconButton { iconName: library.viewMode === "grid" ? "view_list" : "grid_view"; tip: library.viewMode === "grid" ? "Show as a list" : "Show as a grid"; onClicked: library.viewMode = library.viewMode === "grid" ? "list" : "grid" }
            PageIconButton { iconName: "create_new_folder"; tip: "Add a folder to the library"; onClicked: addFolderDialog.open() }
            PageIconButton { iconName: "refresh"; tip: "Look for new videos"; onClicked: library.rescan() }
        }

        // ---- tabs ----
        RowLayout {
            Layout.fillWidth: true
            Layout.leftMargin: 20
            Layout.rightMargin: 20
            Layout.topMargin: 6
            Layout.bottomMargin: 8
            spacing: 6
            visible: !lib.inFolder
            Repeater {
                model: [["all", "All videos"], ["folders", "Folders"], ["recent", "Recent"], ["favorites", "Favorites"]]
                Chip { required property var modelData; text: modelData[1]; checked: library.tab === modelData[0]; onClicked: library.tab = modelData[0] }
            }
            Item { Layout.fillWidth: true }
            BusyIndicator { visible: library.scanning; running: visible; Layout.preferredWidth: 20; Layout.preferredHeight: 20 }
            Text {
                text: library.scanning ? "Looking for videos…" : (library.videoCount === 1 ? "1 video" : library.videoCount + " videos")
                color: Theme.pageTextMuted; font.family: Theme.fontFamily; font.pixelSize: 12
            }
        }

        // ---- content ----
        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true

            Component {
                id: continueHeader
                Item {
                    width: parent ? parent.width : 0
                    height: lib.showContinue ? 196 : 0
                    visible: lib.showContinue
                    ColumnLayout {
                        anchors { fill: parent; leftMargin: 20; rightMargin: 12 }
                        spacing: 6
                        Text { text: "Continue watching"; color: Theme.pageText; font.family: Theme.fontFamily; font.pixelSize: 15; font.weight: Font.DemiBold }
                        ListView {
                            Layout.fillWidth: true
                            Layout.fillHeight: true
                            orientation: ListView.Horizontal
                            spacing: 12
                            clip: true
                            boundsBehavior: Flickable.StopAtBounds
                            model: library.continueWatching
                            ScrollBar.horizontal: ScrollBar { policy: ScrollBar.AsNeeded }
                            delegate: Item {
                                id: cw
                                required property var modelData
                                width: 220; height: 160
                                Rectangle {
                                    id: cwThumb
                                    width: parent.width; height: 124; radius: 6
                                    color: Theme.thumbBg
                                    clip: true
                                    border.color: cwMouse.containsMouse ? Theme.pageAccent : "transparent"
                                    Image { anchors.fill: parent; anchors.margins: 1; source: cw.modelData.thumb; sourceSize: Qt.size(320, 180); asynchronous: true; fillMode: Image.PreserveAspectCrop }
                                    Icon { anchors.centerIn: parent; name: cw.modelData.local ? "play_circle_outline" : "cloud"; size: 36; color: "white"; opacity: cwMouse.containsMouse ? 1 : 0.75 }
                                    Rectangle { anchors { left: parent.left; right: parent.right; bottom: parent.bottom } height: 4; color: "#80000000"
                                        Rectangle { width: parent.width * cw.modelData.progress; height: parent.height; color: Theme.accent } }
                                }
                                Text { anchors { top: cwThumb.bottom; topMargin: 6; left: parent.left; right: parent.right } text: cw.modelData.title; color: Theme.pageText; elide: Text.ElideRight; font.family: Theme.fontFamily; font.pixelSize: 13 }
                                Text { anchors { top: cwThumb.bottom; topMargin: 24; left: parent.left; right: parent.right } text: cw.modelData.remaining; color: Theme.pageTextMuted; font.family: Theme.fontFamily; font.pixelSize: 11 }
                                MouseArea { id: cwMouse; anchors.fill: parent; hoverEnabled: true; cursorShape: Qt.PointingHandCursor; onClicked: lib.host.playLocations([cw.modelData.location]) }
                            }
                        }
                    }
                }
            }

            GridView {
                id: grid
                anchors { fill: parent; leftMargin: 13; rightMargin: 6 }
                visible: library.viewMode === "grid" && count > 0
                clip: true
                model: library.model
                readonly property int columns: Math.max(2, Math.floor(width / 214))
                cellWidth: Math.floor(width / columns)
                cellHeight: Math.round((cellWidth - 24) * 9 / 16) + 76
                boundsBehavior: Flickable.StopAtBounds
                header: continueHeader
                cacheBuffer: 600
                ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }
                delegate: VideoCard {
                    id: cardDelegate
                    width: grid.cellWidth
                    height: grid.cellHeight
                    onActivated: lib.activate(isFolder, path)
                    onMenuRequested: (x, y) => lib.openMenu(cardDelegate, isFolder, path, name, x, y)
                }
            }

            ListView {
                id: list
                anchors { fill: parent; leftMargin: 14; rightMargin: 8 }
                visible: library.viewMode === "list" && count > 0
                clip: true
                model: library.model
                spacing: 2
                boundsBehavior: Flickable.StopAtBounds
                header: continueHeader
                ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }
                delegate: VideoRow {
                    id: rowDelegate
                    width: list.width - 10
                    onActivated: lib.activate(isFolder, path)
                    onMenuRequested: (x, y) => lib.openMenu(rowDelegate, isFolder, path, name, x, y)
                }
            }

            // Empty state (continue watching still shows above it)
            ColumnLayout {
                anchors.centerIn: parent
                visible: grid.count === 0 && list.count === 0 && !lib.onboarding
                width: Math.min(parent.width - 40, 420)
                spacing: 12
                Icon { Layout.alignment: Qt.AlignHCenter; name: library.query.length > 0 ? "search_off" : (library.tab === "favorites" ? "favorite_border" : "video_library"); size: 54; color: Theme.pageTextMuted; opacity: 0.7 }
                Text { Layout.fillWidth: true; horizontalAlignment: Text.AlignHCenter; wrapMode: Text.Wrap; text: library.emptyMessage; color: Theme.pageText; font.family: Theme.fontFamily; font.pixelSize: 15 }
                Text {
                    Layout.fillWidth: true; horizontalAlignment: Text.AlignHCenter; wrapMode: Text.Wrap
                    visible: library.tab === "all" && library.query.length === 0 && !library.scanning
                    text: library.folders.length === 0 ? "CPlay only reads the folders you choose." : "Watching: " + library.folders.map(f => f.replace(/\//g, "\\")).join(", ")
                    color: Theme.pageTextMuted; font.family: Theme.fontFamily; font.pixelSize: 12
                }
                RowLayout {
                    Layout.alignment: Qt.AlignHCenter
                    spacing: 8
                    visible: library.query.length === 0 && (library.tab === "all" || library.tab === "folders")
                    PageButton { text: "Add a folder"; iconName: "create_new_folder"; primary: true; onClicked: addFolderDialog.open() }
                    PageButton { text: "Open a file"; iconName: "folder_open"; onClicked: lib.openFiles() }
                }
            }

            // First start: welcome
            Rectangle {
                anchors.fill: parent
                visible: lib.onboarding
                color: Theme.lightPages ? "#99F3F4F7" : "#B3101318"
                MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons; onWheel: (w) => w.accepted = true }
            }
            Rectangle {
                anchors.centerIn: parent
                visible: lib.onboarding
                width: Math.min(parent.width - 40, 520)
                height: welcomeCol.implicitHeight + 48
                radius: 12
                color: Theme.pageSurface
                border.color: Theme.pageBorder
                ColumnLayout {
                    id: welcomeCol
                    anchors { fill: parent; margins: 24 }
                    spacing: 12
                    RowLayout {
                        spacing: 12
                        Image { source: "qrc:/qt/qml/ChaosPlay/assets/chaosplay-256.png"; sourceSize: Qt.size(48, 48); Layout.preferredWidth: 48; Layout.preferredHeight: 48 }
                        Text { text: "Welcome to CPlay"; color: Theme.pageText; font.family: Theme.fontFamily; font.pixelSize: 22; font.weight: Font.DemiBold }
                    }
                    Text { Layout.fillWidth: true; wrapMode: Text.Wrap; color: Theme.pageText; font.family: Theme.fontFamily; font.pixelSize: 14
                        text: "A video player with every feature and no ads, no tracking, no account." }
                    Text { Layout.fillWidth: true; wrapMode: Text.Wrap; color: Theme.pageTextMuted; font.family: Theme.fontFamily; font.pixelSize: 13
                        text: library.folders.length > 0
                              ? "Your library shows the videos in " + library.folders.map(f => f.replace(/\//g, "\\")).join(", ") + ". Add the other folders where you keep videos, or open your home server from Network."
                              : "Add the folders where you keep videos, or open your home server from Network." }
                    RowLayout {
                        Layout.topMargin: 6
                        spacing: 8
                        PageButton { text: "Add a folder"; iconName: "create_new_folder"; onClicked: addFolderDialog.open() }
                        Item { Layout.fillWidth: true }
                        PageButton { text: "Start watching"; primary: true; onClicked: { app.setSetting("app/onboarded", true); lib.welcomeDismissed = true } }
                    }
                }
            }
        }
    }

    // ---- menus ----
    ChaosMenu {
        id: sortMenu
        Repeater {
            model: library.sortOptions
            ChaosMenuItem { required property var modelData; text: modelData.label; checkable: true; checked: library.sort === modelData.key; onTriggered: library.sort = modelData.key }
        }
    }

    ChaosMenu {
        id: videoMenu
        property string path
        property string name
        ChaosMenuItem { text: "Play"; onTriggered: lib.play(videoMenu.path) }
        ChaosMenuItem { text: "Play from the beginning"; onTriggered: { lib.host.startOverNext = true; lib.play(videoMenu.path) } }
        ChaosMenuItem { text: "Add to the playlist"; enabled: lib.host.active; onTriggered: lib.host.addToQueue([videoMenu.path]) }
        ChaosMenuItem { text: "Play everything shown here"; onTriggered: { const all = library.visiblePaths(); lib.host.playQueue(all, Math.max(0, all.indexOf(videoMenu.path))) } }
        MenuSeparator {}
        ChaosMenuItem {
            text: library.favoriteRevision >= 0 && library.isFavorite(videoMenu.path) ? "Remove from favorites" : "Add to favorites"
            onTriggered: library.setFavorite(videoMenu.path, !library.isFavorite(videoMenu.path))
        }
        ChaosMenuItem { text: "Rename…"; onTriggered: { renameDialog.path = videoMenu.path; renameDialog.open() } }
        ChaosMenuItem { text: "Show in folder"; onTriggered: library.showInFolder(videoMenu.path) }
        ChaosMenuItem { text: "Properties…"; onTriggered: { infoDialog.info = library.info(videoMenu.path); infoDialog.open() } }
        MenuSeparator {}
        ChaosMenuItem { text: "Move to the Recycle Bin…"; onTriggered: { deleteDialog.path = videoMenu.path; deleteDialog.name = videoMenu.name; deleteDialog.open() } }
    }

    ChaosMenu {
        id: folderMenu
        property string path
        property string name
        ChaosMenuItem { text: "Open"; onTriggered: library.openFolder = folderMenu.path }
        ChaosMenuItem { text: "Play all"; onTriggered: lib.host.playQueue(library.pathsInFolder(folderMenu.path), 0) }
        ChaosMenuItem { text: "Add all to the playlist"; enabled: lib.host.active; onTriggered: lib.host.addToQueue(library.pathsInFolder(folderMenu.path)) }
        ChaosMenuItem { text: "Show in Explorer"; onTriggered: library.showInFolder(folderMenu.path) }
    }

    // ---- dialogs ----
    FolderDialog {
        id: addFolderDialog
        title: "Add a folder to the library"
        onAccepted: {
            if (!library.addFolder(selectedFolder)) lib.host.osdMessage("That folder is already in the library")
            app.setSetting("app/onboarded", true)
            lib.welcomeDismissed = true
        }
    }

    Dialog {
        id: renameDialog
        property string path
        title: "Rename"
        modal: true
        anchors.centerIn: parent
        width: Math.min(460, lib.width - 40)
        standardButtons: Dialog.Ok | Dialog.Cancel
        onOpened: {
            const base = path.substring(path.lastIndexOf("/") + 1)
            renameField.text = base.lastIndexOf(".") > 0 ? base.substring(0, base.lastIndexOf(".")) : base
            renameError.text = ""
            renameField.forceActiveFocus()
            renameField.selectAll()
        }
        onAccepted: {
            const err = library.rename(path, renameField.text)
            if (err.length > 0) { renameError.text = err; open() }
        }
        ColumnLayout {
            anchors.fill: parent
            spacing: 8
            TextField { id: renameField; Layout.fillWidth: true; onAccepted: renameDialog.accept() }
            Text { id: renameError; visible: text.length > 0; color: Theme.danger; wrapMode: Text.Wrap; Layout.fillWidth: true }
            Text { text: "The extension stays the same; matching subtitle files are renamed too."; color: Theme.textMuted; wrapMode: Text.Wrap; Layout.fillWidth: true; font.pixelSize: 12 }
        }
    }

    Dialog {
        id: deleteDialog
        property string path
        property string name
        title: "Move to the Recycle Bin?"
        modal: true
        anchors.centerIn: parent
        width: Math.min(460, lib.width - 40)
        standardButtons: Dialog.Yes | Dialog.No
        onAccepted: {
            const err = library.moveToRecycleBin(path)
            lib.host.osdMessage(err.length > 0 ? err : "Moved to the Recycle Bin")
        }
        Text { width: parent.width; wrapMode: Text.Wrap; color: Theme.text; text: "\"" + deleteDialog.name + "\" goes to the Recycle Bin. You can restore it from there." }
    }

    Dialog {
        id: infoDialog
        property var info: ({})
        title: "Properties"
        modal: true
        anchors.centerIn: parent
        width: Math.min(560, lib.width - 40)
        standardButtons: Dialog.Close
        GridLayout {
            anchors.fill: parent
            columns: 2
            columnSpacing: 16
            rowSpacing: 6
            Repeater {
                model: [["Name", "name"], ["Folder", "folder"], ["Size", "size"], ["Length", "duration"], ["Modified", "modified"], ["Watched", "watched"]]
                delegate: Text {
                    required property var modelData
                    required property int index
                    Layout.row: index
                    Layout.column: 0
                    text: modelData[0]
                    color: Theme.textMuted
                }
            }
            Repeater {
                model: ["name", "folder", "size", "duration", "modified", "watched"]
                delegate: TextEdit {
                    required property string modelData
                    required property int index
                    Layout.row: index
                    Layout.column: 1
                    Layout.fillWidth: true
                    readOnly: true
                    selectByMouse: true
                    wrapMode: Text.Wrap
                    text: infoDialog.info[modelData] || ""
                    color: Theme.text
                }
            }
        }
    }
}
