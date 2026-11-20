import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// Add or edit a network source. The connection is tested before saving.
Popup {
    id: dlg
    property string editId: ""
    property string type: "jellyfin"
    property bool busy: false
    property string error: ""
    readonly property var info: net.serverTypes.find(t => t.key === type) || ({})
    readonly property bool needsUser: info.needsUser === true

    function openNew(kind) {
        editId = ""
        type = kind || "jellyfin"
        nameField.text = ""; addressField.text = ""; userField.text = ""; passwordField.text = ""
        error = ""; busy = false
        open()
        addressField.forceActiveFocus()
    }
    function openEdit(id) {
        const s = net.server(id)
        editId = id
        type = s.type
        nameField.text = s.name; addressField.text = s.address; userField.text = s.user; passwordField.text = ""
        error = ""; busy = false
        open()
    }
    function submit() {
        if (busy) return
        error = ""
        busy = true
        net.saveServer({ id: editId, type: type, name: nameField.text, address: addressField.text, user: userField.text, password: passwordField.text })
    }

    modal: true
    focus: true
    anchors.centerIn: Overlay.overlay
    width: Math.min(560, (parent ? parent.width : 600) - 40)
    padding: 22
    closePolicy: busy ? Popup.NoAutoClose : Popup.CloseOnEscape | Popup.CloseOnPressOutside
    background: Rectangle { radius: 12; color: Theme.pageSurface; border.color: Theme.pageBorder }
    Overlay.modal: Rectangle { color: "#99000000" }

    Connections {
        target: net
        function onServerSaved(ok, message, id) {
            if (!dlg.opened) return
            dlg.busy = false
            if (ok) dlg.close()
            else dlg.error = message
        }
    }

    contentItem: ColumnLayout {
        spacing: 10
        Text { text: dlg.editId.length > 0 ? "Edit network source" : "Add a network source"; color: Theme.pageText; font.family: Theme.fontFamily; font.pixelSize: 18; font.weight: Font.DemiBold }

        Flow {
            Layout.fillWidth: true
            spacing: 6
            visible: dlg.editId.length === 0
            Repeater {
                model: net.serverTypes
                Chip { required property var modelData; text: modelData.label; checked: dlg.type === modelData.key; onClicked: { dlg.type = modelData.key; dlg.error = "" } }
            }
        }
        Text { Layout.fillWidth: true; wrapMode: Text.Wrap; text: dlg.info.help || ""; color: Theme.pageTextMuted; font.family: Theme.fontFamily; font.pixelSize: 12 }

        GridLayout {
            Layout.fillWidth: true
            columns: 2
            columnSpacing: 12
            rowSpacing: 8
            Text { text: "Address"; color: Theme.pageText; font.family: Theme.fontFamily; font.pixelSize: 13 }
            PageField { id: addressField; Layout.fillWidth: true; placeholderText: dlg.info.addressHint || ""; onAccepted: dlg.submit() }
            Text { visible: dlg.needsUser; text: "User name"; color: Theme.pageText; font.family: Theme.fontFamily; font.pixelSize: 13 }
            PageField { id: userField; visible: dlg.needsUser; Layout.fillWidth: true; placeholderText: dlg.type === "smb" || dlg.type === "ftp" ? "Empty for guest access" : ""; onAccepted: dlg.submit() }
            Text { visible: dlg.needsUser; text: "Password"; color: Theme.pageText; font.family: Theme.fontFamily; font.pixelSize: 13 }
            PageField {
                id: passwordField
                visible: dlg.needsUser
                Layout.fillWidth: true
                echoMode: TextInput.Password
                placeholderText: dlg.editId.length > 0 ? "Unchanged" : ""
                onAccepted: dlg.submit()
            }
            Text { text: "Name"; color: Theme.pageText; font.family: Theme.fontFamily; font.pixelSize: 13 }
            PageField { id: nameField; Layout.fillWidth: true; placeholderText: "Optional"; onAccepted: dlg.submit() }
        }

        Rectangle {
            Layout.fillWidth: true
            visible: dlg.error.length > 0
            implicitHeight: errText.implicitHeight + 16
            radius: 6
            color: Qt.rgba(1, 0.36, 0.36, 0.12)
            border.color: Theme.danger
            Text { id: errText; anchors { fill: parent; margins: 8 } wrapMode: Text.Wrap; text: dlg.error; color: Theme.lightPages ? "#B42318" : "#FF8A8A"; font.family: Theme.fontFamily; font.pixelSize: 13 }
        }
        Text {
            Layout.fillWidth: true; wrapMode: Text.Wrap
            visible: dlg.needsUser
            text: "Passwords are encrypted for your Windows account and only sent to this server."
            color: Theme.pageTextMuted; font.family: Theme.fontFamily; font.pixelSize: 11
        }

        RowLayout {
            Layout.fillWidth: true
            Layout.topMargin: 6
            spacing: 8
            BusyIndicator { visible: dlg.busy; running: dlg.busy; Layout.preferredWidth: 24; Layout.preferredHeight: 24 }
            Text { visible: dlg.busy; text: "Connecting…"; color: Theme.pageTextMuted; font.family: Theme.fontFamily; font.pixelSize: 13 }
            Item { Layout.fillWidth: true }
            PageButton { text: "Cancel"; enabled: !dlg.busy; onClicked: dlg.close() }
            PageButton { text: dlg.editId.length > 0 ? "Save" : "Connect"; primary: true; enabled: !dlg.busy && addressField.text.trim().length > 0; onClicked: dlg.submit() }
        }
    }
}
