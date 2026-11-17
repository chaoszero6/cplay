import QtQuick

// Seek / volume bar. The thumb is anchored to the track's vertical centre, so it
// stays centred at every size (the Android 1.4.0 seek-bar fix, built in).
Item {
    id: bar
    property real value: 0          // 0..1
    property bool live: false       // emit moved() while dragging (volume); seek bars wait for release
    property var labelFor: null     // function(fraction) -> hover label, optional
    property var marks: []          // fractions drawn as ticks (A-B, bookmarks)
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
            model: bar.marks
            Rectangle {
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

    Rectangle {
        id: hoverLabel
        visible: bar.labelFor !== null && mouse.containsMouse
        color: "#E6171B22"
        radius: 6
        width: hoverText.implicitWidth + 14
        height: 24
        y: -height - 6
        x: Math.max(0, Math.min(bar.width - width, mouse.mouseX - width / 2))
        Text {
            id: hoverText
            anchors.centerIn: parent
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
