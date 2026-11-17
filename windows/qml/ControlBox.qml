import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// Control box (Ctrl+G): picture, audio and subtitle adjustments in one floating
// panel. Every row has a slider, its value and a reset; drag the header to move it.
Rectangle {
    id: box
    required property var host       // PlayerPage
    property int tab: 0
    property real maxHeight: 10000   // set by the page: the room left in the video area

    width: 360
    height: Math.min(header.implicitHeight + content.implicitHeight + 22, maxHeight)
    clip: true
    radius: 8
    color: "#F21A1E24"
    border.color: Theme.chromeBorder

    // Swallow clicks so they don't reach the video (pause)
    MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons; onWheel: (w) => w.accepted = true }

    component Row_: RowLayout {
        id: row
        property string label
        property string valueText
        property real from: -100
        property real to: 100
        property real value: 0
        property bool showReset: true
        signal moved(real v)
        signal reset()
        Layout.fillWidth: true
        spacing: 8
        Text { Layout.preferredWidth: 86; text: row.label; color: Theme.chromeText; font.family: Theme.fontFamily; font.pixelSize: 12; elide: Text.ElideRight }
        SeekBar {
            Layout.fillWidth: true
            Layout.preferredHeight: 22
            live: true
            value: (row.value - row.from) / (row.to - row.from)
            onMoved: (f) => row.moved(row.from + f * (row.to - row.from))
            onReleased: (f) => row.moved(row.from + f * (row.to - row.from))
        }
        Text { Layout.preferredWidth: 56; horizontalAlignment: Text.AlignRight; text: row.valueText; color: Theme.text; font.family: Theme.fontFamily; font.pixelSize: 12 }
        IconButton { iconName: "restart_alt"; iconSize: 15; implicitWidth: 24; implicitHeight: 24; visible: row.showReset; tip: "Reset"; onClicked: row.reset() }
    }

    component Toggle_: AbstractButton {
        id: tg
        checkable: true
        implicitHeight: 28
        implicitWidth: tgText.implicitWidth + 22
        hoverEnabled: true
        background: Rectangle { radius: 4; color: tg.checked ? Theme.menuHover : tg.hovered ? Theme.chromeHover : "transparent"; border.color: tg.checked ? Theme.accent : Theme.chromeBorder }
        contentItem: Text { id: tgText; text: tg.text; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter; color: tg.checked ? "white" : Theme.chromeText; font.family: Theme.fontFamily; font.pixelSize: 12 }
    }

    ColumnLayout {
        id: header
        anchors { left: parent.left; right: parent.right; top: parent.top; margins: 8 }
        spacing: 6

        // Header: drag to move, tabs, close
        RowLayout {
            Layout.fillWidth: true
            spacing: 2
            Item {
                Layout.fillWidth: true
                Layout.preferredHeight: 28
                Text { anchors.verticalCenter: parent.verticalCenter; text: "Control box"; color: Theme.text; font.family: Theme.fontFamily; font.pixelSize: 13; font.weight: Font.DemiBold }
                MouseArea {
                    anchors.fill: parent
                    cursorShape: Qt.SizeAllCursor
                    drag.target: box
                    drag.minimumX: 0; drag.maximumX: box.parent ? box.parent.width - box.width : 0
                    drag.minimumY: 0; drag.maximumY: box.parent ? box.parent.height - box.height : 0
                }
            }
            IconButton { iconName: "restart_alt"; iconSize: 17; tip: "Reset everything on this tab"; onClicked: box.host.resetAdjustments(box.tab) }
            IconButton { iconName: "close"; iconSize: 17; tip: "Close (Ctrl+G)"; onClicked: box.host.showControlBox = false }
        }
        Row {
            spacing: 4
            Repeater {
                model: ["Picture", "Audio", "Subtitles"]
                Toggle_ { required property int index; required property string modelData; text: modelData; checked: box.tab === index; onClicked: box.tab = index }
            }
        }
        Rectangle { Layout.fillWidth: true; height: 1; color: Theme.chromeBorder }
    }

    // Rows scroll when the window is too small to show them all
    Flickable {
        id: scroller
        anchors { left: parent.left; right: parent.right; top: header.bottom; bottom: parent.bottom; margins: 8; topMargin: 6 }
        contentHeight: content.implicitHeight
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        ScrollBar.vertical: ScrollBar { policy: scroller.contentHeight > scroller.height ? ScrollBar.AlwaysOn : ScrollBar.AsNeeded }

    ColumnLayout {
        id: content
        width: scroller.width - 10
        spacing: 6

        // ---- Picture ----
        ColumnLayout {
            visible: box.tab === 0
            Layout.fillWidth: true
            spacing: 2
            Repeater {
                model: [["Brightness", "brightness"], ["Contrast", "contrast"], ["Saturation", "saturation"], ["Hue", "hue"], ["Gamma", "gamma"]]
                Row_ {
                    required property var modelData
                    label: modelData[0]
                    value: box.host.picture[modelData[1]]
                    valueText: (value > 0 ? "+" : "") + Math.round(value)
                    onMoved: (v) => box.host.setPicture(modelData[1], Math.round(v))
                    onReset: box.host.setPicture(modelData[1], 0)
                }
            }
            Row_ {
                label: "Zoom"
                from: -1; to: 1
                value: box.host.zoom
                valueText: tools.zoomPercent(value) + "%"
                onMoved: (v) => box.host.setZoom(Math.round(v * 10) / 10)
                onReset: box.host.setZoom(0)
            }
            Flow {
                Layout.fillWidth: true
                Layout.topMargin: 4
                spacing: 4
                Toggle_ { text: "Rotate " + box.host.rotation + "°"; checkable: false; onClicked: box.host.rotate() }
                Toggle_ { text: "Flip"; checked: box.host.flipped; onClicked: box.host.setFlipped(!box.host.flipped) }
                Toggle_ { text: "Deinterlace"; checked: box.host.deinterlace; onClicked: box.host.setDeinterlace(!box.host.deinterlace) }
            }
            Text { text: "Aspect ratio"; color: Theme.textMuted; font.family: Theme.fontFamily; font.pixelSize: 11; Layout.topMargin: 4 }
            Flow {
                Layout.fillWidth: true
                spacing: 4
                Repeater {
                    model: tools.aspectPresets()
                    Toggle_ { required property var modelData; text: modelData.label; checked: box.host.aspect === modelData.value; onClicked: box.host.setAspect(modelData.value) }
                }
            }
        }

        // ---- Audio ----
        ColumnLayout {
            visible: box.tab === 1
            Layout.fillWidth: true
            spacing: 2
            Row_ {
                label: "Audio delay"
                from: -5000; to: 5000
                value: box.host.audioDelayMs
                valueText: (value > 0 ? "+" : "") + (value / 1000).toFixed(1) + " s"
                onMoved: (v) => box.host.setAudioDelay(Math.round(v / 50) * 50)
                onReset: box.host.setAudioDelay(0)
            }
            Text { Layout.fillWidth: true; wrapMode: Text.Wrap; text: "Ctrl+Minus / Ctrl+Plus move the sound 0.1 s earlier or later."; color: Theme.textMuted; font.family: Theme.fontFamily; font.pixelSize: 11 }
            Flow {
                Layout.fillWidth: true
                Layout.topMargin: 6
                spacing: 4
                Toggle_ { text: "Volume levelling"; checked: box.host.levelling; onClicked: box.host.setLevelling(!box.host.levelling) }
            }
            Text { Layout.fillWidth: true; wrapMode: Text.Wrap; text: "Levelling evens out quiet dialogue and loud scenes."; color: Theme.textMuted; font.family: Theme.fontFamily; font.pixelSize: 11 }
        }

        // ---- Subtitles ----
        ColumnLayout {
            visible: box.tab === 2
            Layout.fillWidth: true
            spacing: 2
            Row_ {
                label: "Delay"
                from: -5000; to: 5000
                value: box.host.subDelayMs
                valueText: (value > 0 ? "+" : "") + (value / 1000).toFixed(1) + " s"
                onMoved: (v) => box.host.setSubDelay(Math.round(v / 50) * 50)
                onReset: box.host.setSubDelay(0)
            }
            Row_ {
                label: "Size"
                from: 0.3; to: 3.0
                value: box.host.subScale
                valueText: Math.round(value * 100) + "%"
                onMoved: (v) => box.host.setSubScale(Math.round(v * 10) / 10)
                onReset: box.host.setSubScale(1.0)
            }
            Row_ {
                label: "Position"
                from: 0; to: 150
                value: box.host.subPos
                valueText: Math.round(value)
                onMoved: (v) => box.host.setSubPos(Math.round(v))
                onReset: box.host.setSubPos(100)
            }
            Text { text: "Text encoding (for subtitles that show wrong letters)"; color: Theme.textMuted; font.family: Theme.fontFamily; font.pixelSize: 11; Layout.topMargin: 4; Layout.fillWidth: true; wrapMode: Text.Wrap }
            Flow {
                Layout.fillWidth: true
                spacing: 4
                Repeater {
                    model: [["Auto", "auto"], ["UTF-8", "utf8"], ["Western", "cp1252"], ["Cyrillic", "cp1251"], ["Arabic", "cp1256"],
                            ["Greek", "cp1253"], ["Turkish", "cp1254"], ["Hebrew", "cp1255"], ["Chinese (S)", "gbk"], ["Chinese (T)", "big5"],
                            ["Japanese", "shift_jis"], ["Korean", "cp949"], ["Thai", "cp874"]]
                    Toggle_ { required property var modelData; text: modelData[0]; checked: box.host.subCodepage === modelData[1]; onClicked: box.host.setSubCodepage(modelData[1]) }
                }
            }
        }
    }
    }
}
