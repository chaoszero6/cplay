import QtQuick

// One Material icon by name, e.g. Icon { name: "push_pin" }
Text {
    property string name
    property int size: 24
    text: name
    color: Theme.text
    font.family: Theme.iconFont
    font.pixelSize: size
    horizontalAlignment: Text.AlignHCenter
    verticalAlignment: Text.AlignVCenter
    renderType: Text.QtRendering
}
