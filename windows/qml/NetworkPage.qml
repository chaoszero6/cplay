import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// Network sources: saved servers, servers found on the home network, and a
// browser for each (folders, Jellyfin libraries with Continue watching / Next up).
Item {
    id: page
    required property var host            // PlayerPage
    property alias serverDialog: serverDialog

    property string serverId: ""
    property var serverInfo: ({})
    property var crumbs: []               // [{name, path}]
    property var entries: []
    property var sections: []             // Jellyfin home rows
    property bool loading: false
    property string error: ""
    property int pending: -1
    property int pendingHome: -1
    readonly property string path: crumbs.length > 0 ? crumbs[crumbs.length - 1].path : ""
    readonly property bool isJellyfin: serverInfo.type === "jellyfin"

    function openServer(id) {
        serverId = id
        serverInfo = net.server(id)
        crumbs = [{ name: serverInfo.name, path: "" }]
        load()
    }
    function closeServer() { serverId = ""; entries = []; sections = []; error = ""; crumbs = [] }
    function enter(entry) {
        crumbs = crumbs.concat([{ name: entry.name, path: entry.id }])
        load()
    }
    function goTo(index) {
        crumbs = crumbs.slice(0, index + 1)
        load()
    }
    function back() {
        if (crumbs.length > 1) goTo(crumbs.length - 2)
        else closeServer()
    }
    function load() {
        loading = true
        error = ""
        entries = []
        sections = []
        pending = net.browse(serverId, path)
        if (isJellyfin && path === "") pendingHome = net.jellyfinHome(serverId)
    }
    function runSearch(text) {
        if (text.trim().length === 0) return
        crumbs = [crumbs[0], { name: "Search: " + text.trim(), path: "search:" + text.trim() }]
        load()
    }
    function play(entry, fromStart) {
        // Queue every video of this folder, starting at the one clicked
        const files = entries.filter(e => !e.isFolder && e.playKey.length > 0)
        const keys = files.map(e => e.playKey)
        const i = Math.max(0, keys.indexOf(entry.playKey))
        if (fromStart) host.startOverNext = true
        host.playQueue(keys.length > 0 ? keys : [entry.playKey], keys.length > 0 ? i : 0)
    }
    function activate(entry) {
        if (entry.isFolder) enter(entry)
        else play(entry, false)
    }

    Connections {
        target: net
        function onBrowseDone(request, list, err) {
            if (request !== page.pending) return
            page.loading = false
            page.entries = list
            page.error = err
        }
        function onHomeDone(request, list, err) {
            if (request === page.pendingHome) page.sections = list
        }
        function onServersChanged() {
            if (page.serverId.length > 0 && net.server(page.serverId).id === "") page.closeServer()
        }
    }

    // ---- overview -----------------------------------------------------------
    Flickable {
        id: overview
        anchors.fill: parent
        visible: page.serverId.length === 0
        contentHeight: overviewCol.implicitHeight + 40
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

        ColumnLayout {
            id: overviewCol
            x: 20
            y: 12
            width: overview.width - 40
            spacing: 10

            RowLayout {
                Layout.fillWidth: true
                Layout.preferredHeight: 44
                spacing: 8
                Text { Layout.fillWidth: true; text: "Network"; color: Theme.pageText; font.family: Theme.fontFamily; font.pixelSize: Theme.headerTitleSize; font.weight: Font.DemiBold }
                PageButton { text: net.discovering ? "Searching…" : "Search the network"; iconName: "wifi_find"; enabled: !net.discovering; onClicked: net.discover() }
                PageButton { text: "Add a source"; iconName: "add"; primary: true; onClicked: serverDialog.openNew("jellyfin") }
            }

            // Saved sources
            Flow {
                Layout.fillWidth: true
                spacing: 12
                visible: net.servers.length > 0
                Repeater {
                    model: net.servers
                    Rectangle {
                        id: card
                        required property var modelData
                        width: Math.min(300, (overviewCol.width - 12) / Math.max(1, Math.floor(overviewCol.width / 280)) - 1)
                        height: 78
                        radius: 10
                        color: cardMouse.containsMouse ? Theme.pageHover : Theme.pageSurface
                        border.color: cardMouse.containsMouse ? Theme.pageAccent : Theme.pageBorder
                        RowLayout {
                            anchors { fill: parent; margins: 14 }
                            spacing: 12
                            Rectangle {
                                Layout.preferredWidth: 44; Layout.preferredHeight: 44; radius: 10
                                color: Theme.pageSelected
                                Icon { anchors.centerIn: parent; name: card.modelData.icon; size: 24; color: Theme.pageAccent }
                            }
                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 2
                                Text { Layout.fillWidth: true; text: card.modelData.name; color: Theme.pageText; elide: Text.ElideRight; font.family: Theme.fontFamily; font.pixelSize: 14; font.weight: Font.Medium }
                                Text { Layout.fillWidth: true; text: card.modelData.typeLabel + (card.modelData.user ? "  ·  " + card.modelData.user : ""); color: Theme.pageTextMuted; elide: Text.ElideRight; font.family: Theme.fontFamily; font.pixelSize: 12 }
                            }
                        }
                        MouseArea {
                            id: cardMouse
                            anchors.fill: parent
                            hoverEnabled: true
                            acceptedButtons: Qt.LeftButton | Qt.RightButton
                            cursorShape: Qt.PointingHandCursor
                            onClicked: (m) => {
                                if (m.button === Qt.RightButton) { serverMenu.serverId = card.modelData.id; serverMenu.popup() }
                                else page.openServer(card.modelData.id)
                            }
                        }
                    }
                }
            }

            // Empty state
            ColumnLayout {
                Layout.fillWidth: true
                Layout.topMargin: 30
                visible: net.servers.length === 0
                spacing: 10
                Icon { Layout.alignment: Qt.AlignHCenter; name: "lan"; size: 54; color: Theme.pageTextMuted; opacity: 0.7 }
                Text { Layout.alignment: Qt.AlignHCenter; text: "Play videos from your home network"; color: Theme.pageText; font.family: Theme.fontFamily; font.pixelSize: 16 }
                Text {
                    Layout.alignment: Qt.AlignHCenter
                    Layout.maximumWidth: 520
                    horizontalAlignment: Text.AlignHCenter
                    wrapMode: Text.Wrap
                    text: "Jellyfin, Nextcloud, WebDAV, Windows shares (SMB), FTP, NFS and DLNA media servers. Add one, or search the network for media servers."
                    color: Theme.pageTextMuted; font.family: Theme.fontFamily; font.pixelSize: 13
                }
            }

            // Found on the network
            ColumnLayout {
                Layout.fillWidth: true
                Layout.topMargin: 14
                visible: net.discovering || net.discovered.length > 0
                spacing: 6
                RowLayout {
                    spacing: 8
                    Text { text: "Found on your network"; color: Theme.pageText; font.family: Theme.fontFamily; font.pixelSize: 15; font.weight: Font.DemiBold }
                    BusyIndicator { visible: net.discovering; running: visible; Layout.preferredWidth: 20; Layout.preferredHeight: 20 }
                }
                Text { visible: !net.discovering && net.discovered.length === 0; text: "No media servers answered."; color: Theme.pageTextMuted; font.family: Theme.fontFamily; font.pixelSize: 13 }
                Repeater {
                    model: net.discovered
                    Rectangle {
                        id: found
                        required property var modelData
                        required property int index
                        Layout.fillWidth: true
                        Layout.maximumWidth: 620
                        height: 52
                        radius: 8
                        color: Theme.pageSurface
                        border.color: Theme.pageBorder
                        RowLayout {
                            anchors { fill: parent; leftMargin: 14; rightMargin: 8 }
                            spacing: 10
                            Icon { name: "devices"; size: 22; color: Theme.pageAccent }
                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 0
                                Text { text: found.modelData.name; color: Theme.pageText; font.family: Theme.fontFamily; font.pixelSize: 14 }
                                Text { text: "DLNA media server  ·  " + found.modelData.host; color: Theme.pageTextMuted; font.family: Theme.fontFamily; font.pixelSize: 11 }
                            }
                            PageButton { text: "Add"; iconName: "add"; onClicked: net.addDiscovered(found.index) }
                        }
                    }
                }
            }
        }
    }

    // ---- browser ------------------------------------------------------------
    ColumnLayout {
        anchors.fill: parent
        visible: page.serverId.length > 0
        spacing: 0

        RowLayout {
            Layout.fillWidth: true
            Layout.leftMargin: 14
            Layout.rightMargin: 14
            Layout.topMargin: 12
            Layout.preferredHeight: 44
            spacing: 6
            PageIconButton { iconName: "arrow_back"; tip: "Back"; onClicked: page.back() }
            // Breadcrumbs
            Flickable {
                Layout.fillWidth: true
                Layout.preferredHeight: 36
                contentWidth: crumbRow.implicitWidth
                clip: true
                boundsBehavior: Flickable.StopAtBounds
                contentX: Math.max(0, contentWidth - width)
                Row {
                    id: crumbRow
                    height: parent.height
                    spacing: 2
                    Repeater {
                        model: page.crumbs
                        Row {
                            id: crumb
                            required property var modelData
                            required property int index
                            readonly property bool last: index === page.crumbs.length - 1
                            height: crumbRow.height
                            spacing: 2
                            Icon { visible: crumb.index > 0; anchors.verticalCenter: parent.verticalCenter; name: "chevron_right"; size: 18; color: Theme.pageTextMuted }
                            Text {
                                anchors.verticalCenter: parent.verticalCenter
                                text: crumb.modelData.name
                                color: crumb.last ? Theme.pageText : Theme.pageAccent
                                font.family: Theme.fontFamily
                                font.pixelSize: crumb.index === 0 ? 18 : 14
                                font.weight: crumb.index === 0 ? Font.DemiBold : Font.Normal
                                MouseArea { anchors.fill: parent; enabled: !crumb.last; cursorShape: Qt.PointingHandCursor; onClicked: page.goTo(crumb.index) }
                            }
                        }
                    }
                }
            }
            PageField {
                visible: page.isJellyfin
                Layout.preferredWidth: 220
                placeholderText: "Search this server"
                onAccepted: page.runSearch(text)
            }
            PageIconButton { iconName: "refresh"; tip: "Reload"; onClicked: page.load() }
        }

        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true

            Component {
                id: jellyfinHeader
                Column {
                    width: parent ? parent.width : 0
                    spacing: 8
                    visible: page.sections.length > 0
                    Repeater {
                        model: page.sections
                        Column {
                            id: section
                            required property var modelData
                            width: parent.width
                            spacing: 6
                            Text { leftPadding: 6; text: section.modelData.title; color: Theme.pageText; font.family: Theme.fontFamily; font.pixelSize: 15; font.weight: Font.DemiBold }
                            ListView {
                                width: parent.width
                                height: 200
                                orientation: ListView.Horizontal
                                spacing: 12
                                clip: true
                                boundsBehavior: Flickable.StopAtBounds
                                model: section.modelData.entries
                                ScrollBar.horizontal: ScrollBar { policy: ScrollBar.AsNeeded }
                                delegate: Item {
                                    id: poster
                                    required property var modelData
                                    width: 220; height: 190
                                    Rectangle {
                                        id: art
                                        width: parent.width; height: 124; radius: 6; clip: true
                                        color: Theme.thumbBg
                                        border.color: posterMouse.containsMouse ? Theme.pageAccent : "transparent"
                                        Image { id: posterImg; anchors.fill: parent; anchors.margins: 1; source: poster.modelData.imageUrl; sourceSize: Qt.size(400, 225); asynchronous: true; fillMode: Image.PreserveAspectCrop }
                                        Icon { anchors.centerIn: parent; visible: posterImg.status !== Image.Ready && !posterMouse.containsMouse; name: "movie"; size: 34; color: Theme.pageTextMuted; opacity: 0.6 }
                                        Icon { anchors.centerIn: parent; name: "play_circle_outline"; size: 36; color: "white"; opacity: posterMouse.containsMouse ? 1 : 0 }
                                        Rectangle { visible: poster.modelData.progress > 0; anchors { left: parent.left; right: parent.right; bottom: parent.bottom } height: 4; color: "#80000000"
                                            Rectangle { width: parent.width * poster.modelData.progress; height: parent.height; color: Theme.accent } }
                                    }
                                    Text { anchors { top: art.bottom; topMargin: 6; left: parent.left; right: parent.right } text: poster.modelData.name; color: Theme.pageText; elide: Text.ElideRight; font.family: Theme.fontFamily; font.pixelSize: 13 }
                                    Text { anchors { top: art.bottom; topMargin: 24; left: parent.left; right: parent.right } text: poster.modelData.subtitle; color: Theme.pageTextMuted; elide: Text.ElideRight; font.family: Theme.fontFamily; font.pixelSize: 11 }
                                    MouseArea {
                                        id: posterMouse
                                        anchors.fill: parent
                                        hoverEnabled: true
                                        cursorShape: Qt.PointingHandCursor
                                        acceptedButtons: Qt.LeftButton | Qt.RightButton
                                        onClicked: (m) => {
                                            if (m.button === Qt.RightButton) { entryMenu.entry = poster.modelData; entryMenu.popup() }
                                            else page.host.playQueue([poster.modelData.playKey], 0)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Text { visible: page.entries.length > 0; leftPadding: 6; topPadding: 6; text: "Libraries"; color: Theme.pageText; font.family: Theme.fontFamily; font.pixelSize: 15; font.weight: Font.DemiBold }
                }
            }

            ListView {
                id: list
                anchors { fill: parent; leftMargin: 14; rightMargin: 8; topMargin: 4 }
                clip: true
                model: page.entries
                spacing: 2
                boundsBehavior: Flickable.StopAtBounds
                header: page.isJellyfin && page.path === "" ? jellyfinHeader : null
                ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }
                delegate: Rectangle {
                    id: row
                    required property var modelData
                    required property int index
                    width: list.width - 10
                    height: 58
                    radius: 6
                    color: rowMouse.containsMouse ? Theme.pageHover : (index % 2 === 1 ? Qt.rgba(0.5, 0.5, 0.5, 0.04) : "transparent")
                    RowLayout {
                        anchors { fill: parent; leftMargin: 8; rightMargin: 12 }
                        spacing: 12
                        Rectangle {
                            Layout.preferredWidth: 76; Layout.preferredHeight: 43; radius: 4
                            color: row.modelData.imageUrl ? Theme.thumbBg : "transparent"
                            clip: true
                            Image { id: rowImg; anchors.fill: parent; visible: row.modelData.imageUrl.length > 0; source: row.modelData.imageUrl; sourceSize: Qt.size(160, 90); asynchronous: true; fillMode: Image.PreserveAspectCrop }
                            Icon {
                                anchors.centerIn: parent
                                visible: rowImg.status !== Image.Ready
                                name: row.modelData.isFolder ? (row.modelData.kind === "share" ? "folder_shared" : "folder") : "movie"
                                size: 26
                                color: row.modelData.isFolder ? Theme.accent2 : Theme.pageAccent
                            }
                            Rectangle { visible: row.modelData.progress > 0; anchors { left: parent.left; right: parent.right; bottom: parent.bottom } height: 3; color: "#80000000"
                                Rectangle { width: parent.width * row.modelData.progress; height: parent.height; color: Theme.accent } }
                        }
                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 2
                            Text { Layout.fillWidth: true; text: row.modelData.name; color: Theme.pageText; elide: Text.ElideRight; font.family: Theme.fontFamily; font.pixelSize: 13; font.weight: Font.Medium }
                            Text { Layout.fillWidth: true; visible: text.length > 0; text: row.modelData.subtitle; color: Theme.pageTextMuted; elide: Text.ElideRight; font.family: Theme.fontFamily; font.pixelSize: 11 }
                        }
                        Icon { visible: row.modelData.played; name: "check_circle"; size: 15; color: Theme.success }
                        Text { Layout.preferredWidth: 64; horizontalAlignment: Text.AlignRight; text: row.modelData.durationLabel; color: Theme.pageTextMuted; font.family: Theme.fontFamily; font.pixelSize: 12 }
                        Text { visible: row.width > 560; Layout.preferredWidth: 74; horizontalAlignment: Text.AlignRight; text: row.modelData.sizeLabel; color: Theme.pageTextMuted; font.family: Theme.fontFamily; font.pixelSize: 12 }
                        Icon { visible: row.modelData.isFolder; name: "chevron_right"; size: 20; color: Theme.pageTextMuted }
                    }
                    MouseArea {
                        id: rowMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        acceptedButtons: Qt.LeftButton | Qt.RightButton
                        cursorShape: Qt.PointingHandCursor
                        onClicked: (m) => {
                            if (m.button === Qt.RightButton && !row.modelData.isFolder) { entryMenu.entry = row.modelData; entryMenu.popup() }
                            else page.activate(row.modelData)
                        }
                    }
                }
            }

            ColumnLayout {
                anchors.centerIn: parent
                visible: !page.loading && page.entries.length === 0 && page.sections.length === 0
                width: Math.min(parent.width - 40, 440)
                spacing: 10
                Icon { Layout.alignment: Qt.AlignHCenter; name: page.error.length > 0 ? "cloud_off" : "folder_open"; size: 48; color: page.error.length > 0 ? Theme.danger : Theme.pageTextMuted }
                Text { Layout.fillWidth: true; horizontalAlignment: Text.AlignHCenter; wrapMode: Text.Wrap; text: page.error.length > 0 ? page.error : "No videos here"; color: Theme.pageText; font.family: Theme.fontFamily; font.pixelSize: 14 }
                RowLayout {
                    Layout.alignment: Qt.AlignHCenter
                    visible: page.error.length > 0
                    spacing: 8
                    PageButton { text: "Try again"; iconName: "refresh"; onClicked: page.load() }
                    PageButton { text: "Edit source"; iconName: "edit"; onClicked: serverDialog.openEdit(page.serverId) }
                }
            }
            BusyIndicator { anchors.centerIn: parent; running: page.loading; visible: page.loading }
        }
    }

    ChaosMenu {
        id: serverMenu
        property string serverId
        ChaosMenuItem { text: "Open"; onTriggered: page.openServer(serverMenu.serverId) }
        ChaosMenuItem { text: "Edit…"; onTriggered: serverDialog.openEdit(serverMenu.serverId) }
        MenuSeparator {}
        ChaosMenuItem { text: "Remove"; onTriggered: net.removeServer(serverMenu.serverId) }
    }

    ChaosMenu {
        id: entryMenu
        property var entry: ({})
        ChaosMenuItem { text: "Play"; onTriggered: page.play(entryMenu.entry, false) }
        ChaosMenuItem { text: "Play from the beginning"; onTriggered: page.play(entryMenu.entry, true) }
        ChaosMenuItem { text: "Add to the playlist"; enabled: page.host.active; onTriggered: page.host.addToQueue([entryMenu.entry.playKey]) }
        ChaosMenuItem { text: "Play on TV…"; onTriggered: page.host.castLocations([entryMenu.entry.playKey]) }
    }

    ServerDialog { id: serverDialog }
}
