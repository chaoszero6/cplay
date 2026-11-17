#include "Platform.h"

#include <QWindow>

#ifdef Q_OS_WIN
#include <windows.h>
#include <dwmapi.h>
#endif

namespace Platform {

void setAlwaysOnTop(QWindow *window, bool onTop)
{
    if (!window) return;
#ifdef Q_OS_WIN
    // SetWindowPos changes the z-order in place: no flicker, no window re-creation
    HWND hwnd = reinterpret_cast<HWND>(window->winId());
    SetWindowPos(hwnd, onTop ? HWND_TOPMOST : HWND_NOTOPMOST, 0, 0, 0, 0,
                 SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
#endif
    // Keep Qt's own flag in sync so it survives fullscreen/restore transitions
    window->setFlag(Qt::WindowStaysOnTopHint, onTop);
}

void keepAwake(bool awake)
{
#ifdef Q_OS_WIN
    SetThreadExecutionState(awake ? (ES_CONTINUOUS | ES_DISPLAY_REQUIRED | ES_SYSTEM_REQUIRED) : ES_CONTINUOUS);
#else
    Q_UNUSED(awake)
#endif
}

void useDarkTitleBar(QWindow *window, bool dark)
{
#ifdef Q_OS_WIN
    if (!window) return;
    BOOL value = dark ? TRUE : FALSE;
    constexpr DWORD DWMWA_USE_IMMERSIVE_DARK_MODE_ = 20;
    DwmSetWindowAttribute(reinterpret_cast<HWND>(window->winId()), DWMWA_USE_IMMERSIVE_DARK_MODE_, &value, sizeof(value));
#else
    Q_UNUSED(window) Q_UNUSED(dark)
#endif
}

} // namespace Platform
