import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// Popover with a title and a list of choices; the selected one has a check.
Popup {
    id: popup
    property string title
    property var choices: []        // [{ label, value, selected }]
    property var footer: null       // optional { label, action }
    signal chosen(var value)

    padding: 8
    width: 300
    modal: false
    focus: true
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutsideParent
    background: Rectangle { color: Theme.surface; radius: Theme.radiusCard; border.color: Theme.surface3 }
    enter: Transition { NumberAnimation { property: "opacity"; from: 0; to: 1; duration: 120 } }
    exit: Transition { NumberAnimation { property: "opacity"; from: 1; to: 0; duration: 100 } }

    contentItem: ColumnLayout {
        spacing: 2
        Text {
            text: popup.title
            color: Theme.textMuted
            font.family: Theme.fontFamily
            font.pixelSize: 12
            font.weight: Font.DemiBold
            Layout.margins: 8
        }
        ListView {
            Layout.fillWidth: true
            Layout.preferredHeight: Math.min(contentHeight, 360)
            clip: true
            model: popup.choices
            boundsBehavior: Flickable.StopAtBounds
            delegate: ItemDelegate {
                required property var modelData
                width: ListView.view.width
                height: 40
                hoverEnabled: true
                background: Rectangle { radius: 8; color: parent.hovered ? Theme.surface2 : "transparent" }
                contentItem: RowLayout {
                    spacing: 8
                    Icon { name: "check"; size: 18; color: Theme.accent; opacity: modelData.selected ? 1 : 0 }
                    Text {
                        Layout.fillWidth: true
                        text: modelData.label
                        color: modelData.selected ? Theme.accent : Theme.text
                        elide: Text.ElideRight
                        font.family: Theme.fontFamily
                        font.pixelSize: 14
                    }
                }
                onClicked: { popup.chosen(modelData.value); popup.close() }
            }
        }
        ItemDelegate {
            visible: popup.footer !== null
            Layout.fillWidth: true
            height: 40
            background: Rectangle { radius: 8; color: parent.hovered ? Theme.surface2 : "transparent" }
            contentItem: RowLayout {
                spacing: 8
                Icon { name: "add"; size: 18; color: Theme.accent }
                Text {
                    text: popup.footer ? popup.footer.label : ""
                    color: Theme.accent
                    font.family: Theme.fontFamily
                    font.pixelSize: 14
                }
            }
            onClicked: { popup.close(); if (popup.footer) popup.footer.action() }
        }
    }
}
