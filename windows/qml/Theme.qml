pragma Singleton
import QtQuick

// Same tokens as android/.../ui/theme (ChaosColors, ChaosSpacing, ChaosType, Motion)
QtObject {
    readonly property color bg: "#0E1116"
    readonly property color surface: "#171B22"
    readonly property color surface2: "#1F242D"
    readonly property color surface3: "#2A303B"
    readonly property color text: "#EDEFF3"
    readonly property color textMuted: "#9AA3B2"
    readonly property color accent: "#4DA3FF"
    readonly property color accent2: "#FFB84D"
    readonly property color danger: "#FF5C5C"
    readonly property color success: "#3DD68C"
    readonly property color scrim: "#8C000000"

    // Window chrome of the classic desktop-player skin
    readonly property color chromeTop: "#1F242C"
    readonly property color chromeBottom: "#14171C"
    readonly property color chromeBorder: "#2C323C"
    readonly property color chromeText: "#C9CFD8"
    readonly property color chromeIcon: "#AAB2BE"
    readonly property color chromeHover: "#2E3540"
    readonly property color closeHover: "#E5484D"
    readonly property color menuBg: "#1A1E24"
    readonly property color menuHover: "#27415F"
    readonly property int titleBarHeight: 32
    readonly property int controlBarHeight: 72

    readonly property int xs: 4
    readonly property int s: 8
    readonly property int m: 12
    readonly property int l: 16
    readonly property int xl: 24
    readonly property int xxl: 32
    readonly property int radiusCard: 12
    readonly property int radiusButton: 8

    readonly property string fontFamily: Qt.platform.os === "windows" ? "Segoe UI Variable Text" : Qt.application.font.family
    readonly property string iconFont: "Material Icons"
    readonly property int headerTitleSize: 20
    readonly property int headingSize: 18
    readonly property int bodySize: 14
    readonly property int captionSize: 12

    // Library, network and settings pages follow the Appearance setting (dark/light);
    // the player skin (title bar, control bar, menus, panels) always stays dark
    property bool lightPages: false
    readonly property color pageBg: lightPages ? "#F3F4F7" : "#101318"
    readonly property color pageSurface: lightPages ? "#FFFFFF" : "#171B22"
    readonly property color pageSurface2: lightPages ? "#E9ECF1" : "#1F242D"
    readonly property color pageBorder: lightPages ? "#D6DAE1" : "#2A303B"
    readonly property color pageHover: lightPages ? "#E2E9F4" : "#232A35"
    readonly property color pageSelected: lightPages ? "#D3E4FA" : "#27415F"
    readonly property color pageText: lightPages ? "#161A20" : "#EDEFF3"
    readonly property color pageTextMuted: lightPages ? "#5D6573" : "#9AA3B2"
    readonly property color pageAccent: lightPages ? "#1F6FD1" : "#4DA3FF"
    readonly property color sidebarBg: lightPages ? "#E9ECF1" : "#14171C"
    readonly property color thumbBg: lightPages ? "#D9DDE4" : "#0B0D10"

    readonly property int fadeMs: 200
    readonly property int autoHideMs: 3000
    readonly property int bufferingDelayMs: 400
}
