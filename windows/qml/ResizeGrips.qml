import QtQuick

// Invisible handles on the edges and corners of the frameless window.
Item {
    id: grips
    property int edge: 5
    property int corner: 12
    anchors.fill: parent

    component Grip: MouseArea {
        property int edges
        acceptedButtons: Qt.LeftButton
        onPressed: Window.window.startSystemResize(edges)
        onReleased: windowCtl.saveGeometry()
    }

    Grip { edges: Qt.LeftEdge; cursorShape: Qt.SizeHorCursor; x: 0; y: grips.corner; width: grips.edge; height: parent.height - 2 * grips.corner }
    Grip { edges: Qt.RightEdge; cursorShape: Qt.SizeHorCursor; x: parent.width - grips.edge; y: grips.corner; width: grips.edge; height: parent.height - 2 * grips.corner }
    Grip { edges: Qt.TopEdge; cursorShape: Qt.SizeVerCursor; x: grips.corner; y: 0; width: parent.width - 2 * grips.corner; height: grips.edge }
    Grip { edges: Qt.BottomEdge; cursorShape: Qt.SizeVerCursor; x: grips.corner; y: parent.height - grips.edge; width: parent.width - 2 * grips.corner; height: grips.edge }
    Grip { edges: Qt.LeftEdge | Qt.TopEdge; cursorShape: Qt.SizeFDiagCursor; x: 0; y: 0; width: grips.corner; height: grips.corner }
    Grip { edges: Qt.RightEdge | Qt.BottomEdge; cursorShape: Qt.SizeFDiagCursor; x: parent.width - grips.corner; y: parent.height - grips.corner; width: grips.corner; height: grips.corner }
    Grip { edges: Qt.RightEdge | Qt.TopEdge; cursorShape: Qt.SizeBDiagCursor; x: parent.width - grips.corner; y: 0; width: grips.corner; height: grips.corner }
    Grip { edges: Qt.LeftEdge | Qt.BottomEdge; cursorShape: Qt.SizeBDiagCursor; x: 0; y: parent.height - grips.corner; width: grips.corner; height: grips.corner }
}
