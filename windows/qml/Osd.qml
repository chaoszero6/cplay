import QtQuick

// On-screen message in the top-left corner of the video ("Volume 80%"),
// outlined so it reads on any picture; fades out by itself.
Text {
    id: osd
    function show(message) { text = message; opacity = 1; hideTimer.restart() }
    opacity: 0
    visible: opacity > 0
    color: "white"
    style: Text.Outline
    styleColor: "#C0000000"
    font.family: Theme.fontFamily
    font.pixelSize: 20
    font.weight: Font.DemiBold
    Behavior on opacity { NumberAnimation { duration: 250 } }
    Timer { id: hideTimer; interval: 1600; onTriggered: osd.opacity = 0 }
}
