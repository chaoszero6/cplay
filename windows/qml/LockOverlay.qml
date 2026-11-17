import QtQuick

// Lock mode (spec 8.2): the video keeps playing, every click, wheel and key is
// ignored. A small "Locked" pill appears on any input; hold Esc for 3 seconds
// to unlock (a ring fills while holding).
Item {
    id: lock
    property bool holding: false
    signal unlockRequested()

    function hint() { pill.opacity = 1; pillTimer.restart() }

    MouseArea {
        anchors.fill: parent
        hoverEnabled: true
        acceptedButtons: Qt.AllButtons
        cursorShape: Qt.ForbiddenCursor
        onPressed: lock.hint()
        onWheel: (w) => { w.accepted = true; lock.hint() }
        onPositionChanged: if (pill.opacity === 0) lock.hint()
    }

    Rectangle {
        id: pill
        anchors.horizontalCenter: parent.horizontalCenter
        y: 48
        width: pillRow.implicitWidth + 32
        height: 40
        radius: 20
        color: "#E61A1E24"
        border.color: Theme.chromeBorder
        opacity: 0
        Behavior on opacity { NumberAnimation { duration: 250 } }
        Row {
            id: pillRow
            anchors.centerIn: parent
            spacing: 10
            Item {
                width: 22; height: 22
                anchors.verticalCenter: parent.verticalCenter
                Icon { anchors.centerIn: parent; name: "lock"; size: 18; color: Theme.accent }
                // Progress ring while Esc is held
                Canvas {
                    id: ring
                    anchors.fill: parent
                    property real progress: 0
                    onProgressChanged: requestPaint()
                    onPaint: {
                        const ctx = getContext("2d")
                        ctx.reset()
                        if (progress <= 0) return
                        ctx.lineWidth = 2.5
                        ctx.strokeStyle = Theme.accent
                        ctx.beginPath()
                        ctx.arc(width / 2, height / 2, width / 2 - 1.5, -Math.PI / 2, -Math.PI / 2 + progress * 2 * Math.PI)
                        ctx.stroke()
                    }
                    NumberAnimation on progress {
                        id: holdAnim
                        running: lock.holding
                        from: 0; to: 1; duration: 3000
                        onFinished: if (lock.holding) { lock.holding = false; lock.unlockRequested() }
                    }
                }
            }
            Text {
                anchors.verticalCenter: parent.verticalCenter
                text: lock.holding ? "Keep holding Esc…" : "Locked  ·  hold Esc for 3 seconds to unlock"
                color: Theme.text
                font.family: Theme.fontFamily
                font.pixelSize: 13
            }
        }
        Timer { id: pillTimer; interval: 2200; onTriggered: if (!lock.holding) pill.opacity = 0 }
    }

    onHoldingChanged: {
        if (holding) { pill.opacity = 1; pillTimer.stop() }
        else { ring.progress = 0; pillTimer.restart() }
    }
}
