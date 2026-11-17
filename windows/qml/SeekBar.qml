import QtQuick

// Seek / volume bar. The thumb is anchored to the track's vertical centre, so it
// stays centred at every size (the Android 1.4.0 seek-bar fix, built in).
Item {
    id: bar
    property real value: 0          // 0..1
    property bool live: false       // emit moved() while dragging (volume); seek bars wait for release
    property var labelFor: null     // function(fraction) -> hover label, optional
    property var marks: []          // fractions drawn as amber ticks (A-B repeat)
    property var minorMarks: []     // fractions drawn as small white ticks (bookmarks)
    property string previewSource: "" // location for the thumbnail above the hover time
    property real previewDurationMs: 0
    property color fillColor: Theme.accent
    readonly property bool dragging: mouse.pressed
    readonly property real shownValue: dragging ? dragValue : value
    property real dragValue: 0

    signal moved(real fraction)
    signal released(real fraction)

    implicitHeight: 24
    implicitWidth: 200
    Accessible.role: Accessible.Slider

    readonly property real thumbBig: 16
    readonly property bool active: mouse.containsMouse || mouse.pressed

    Rectangle {
        id: track
        x: bar.thumbBig / 2
        width: bar.width - bar.thumbBig
        height: bar.active ? 6 : 4
        anchors.verticalCenter: parent.verticalCenter
        radius: height / 2
        color: Qt.rgba(1, 1, 1, 0.28)
        Behavior on height { NumberAnimation { duration: 120 } }

        Rectangle {
            width: track.width * Math.max(0, Math.min(1, bar.shownValue))
            height: parent.height
            radius: parent.radius
            color: bar.fillColor
        }
        Repeater {
            model: bar.minorMarks
            Rectangle {
                required property real modelData
                width: 2; height: 8; radius: 1
                color: "white"
                opacity: 0.85
                x: track.width * modelData - 1
                anchors.verticalCenter: parent.verticalCenter
            }
        }
        Repeater {
            model: bar.marks
            Rectangle {
                required property real modelData
                width: 3; height: 12; radius: 1
                color: Theme.accent2
                x: track.width * modelData - 1
                anchors.verticalCenter: parent.verticalCenter
            }
        }
    }

    Rectangle {
        id: thumb
        width: bar.active ? bar.thumbBig : 12
        height: width
        radius: width / 2
        color: bar.fillColor
        anchors.verticalCenter: track.verticalCenter
        x: track.x + track.width * Math.max(0, Math.min(1, bar.shownValue)) - width / 2
        Behavior on width { NumberAnimation { duration: 120 } }
    }

    // Hover: thumbnail (when a preview source is set) and time above the pointer
    Rectangle {
        id: hoverLabel
        visible: bar.labelFor !== null && mouse.containsMouse
        color: "#E6171B22"
        radius: 6
        border.color: previewImage.visible ? Theme.chromeBorder : "transparent"
        width: Math.max(hoverText.implicitWidth + 14, previewImage.visible ? previewImage.width + 8 : 0)
        height: 24 + (previewImage.visible ? previewImage.height + 6 : 0)
        y: -height - 6
        x: Math.max(-bar.x, Math.min(bar.width - width, mouse.mouseX - width / 2))
        Image {
            id: previewImage
            readonly property real stepMs: Math.max(1000, bar.previewDurationMs / 300)
            readonly property real wantedMs: Math.round(mouse.fractionAt(mouse.mouseX) * bar.previewDurationMs / stepMs) * stepMs
            visible: bar.previewSource.length > 0 && bar.previewDurationMs > 0 && hoverLabel.visible
            anchors { top: parent.top; topMargin: 4; horizontalCenter: parent.horizontalCenter }
            width: 192; height: 108
            fillMode: Image.PreserveAspectFit
            asynchronous: true
            cache: false
            retainWhileLoading: true
            sourceSize: Qt.size(192, 108)
            source: visible ? "image://preview/" + wantedMs + "/" + encodeURIComponent(bar.previewSource) : ""
            Rectangle { anchors.fill: parent; color: "black"; z: -1 }
        }
        Text {
            id: hoverText
            anchors { horizontalCenter: parent.horizontalCenter; bottom: parent.bottom; bottomMargin: 4 }
            color: Theme.text
            font.pixelSize: 12
            font.family: Theme.fontFamily
            text: bar.labelFor ? bar.labelFor(mouse.fractionAt(mouse.mouseX)) : ""
        }
    }

    MouseArea {
        id: mouse
        anchors.fill: parent
        anchors.topMargin: -6
        anchors.bottomMargin: -6
        hoverEnabled: true
        preventStealing: true
        cursorShape: Qt.PointingHandCursor
        function fractionAt(x) { return Math.max(0, Math.min(1, (x - track.x) / track.width)) }
        onPressed: (m) => { bar.dragValue = fractionAt(m.x); if (bar.live) bar.moved(bar.dragValue) }
        onPositionChanged: (m) => { if (pressed) { bar.dragValue = fractionAt(m.x); if (bar.live) bar.moved(bar.dragValue) } }
        onReleased: (m) => { bar.dragValue = fractionAt(m.x); bar.released(bar.dragValue) }
        onWheel: (w) => w.accepted = false
    }
}
