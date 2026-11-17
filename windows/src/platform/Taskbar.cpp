#include "Taskbar.h"

#include <QFont>
#include <QGuiApplication>
#include <QImage>
#include <QPainter>
#include <algorithm>

#ifdef Q_OS_WIN
#include <windows.h>
#include <shobjidl.h>

namespace {
enum ButtonId { IdPrevious = 41, IdPlayPause = 42, IdNext = 43 };

HICON iconFromGlyph(const QString &glyph)
{
    // Material Icons glyph drawn white on transparent, 32 px
    QImage img(32, 32, QImage::Format_ARGB32_Premultiplied);
    img.fill(Qt::transparent);
    QPainter p(&img);
    QFont f(QStringLiteral("Material Icons"));
    f.setPixelSize(28);
    p.setFont(f);
    p.setPen(Qt::white);
    p.drawText(img.rect(), Qt::AlignCenter, glyph);
    p.end();
    return img.toHICON();
}
}
#endif

Taskbar::Taskbar(QObject *parent) : QObject(parent)
{
#ifdef Q_OS_WIN
    // COM is normally set up by Qt already; this is harmless if so
    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    m_buttonCreatedMsg = RegisterWindowMessageW(L"TaskbarButtonCreated");
    ITaskbarList3 *tb = nullptr;
    if (SUCCEEDED(CoCreateInstance(CLSID_TaskbarList, nullptr, CLSCTX_INPROC_SERVER, IID_ITaskbarList3,
                                   reinterpret_cast<void **>(&tb)))) {
        if (SUCCEEDED(tb->HrInit())) m_taskbar = tb;
        else tb->Release();
    }
    m_icons[0] = iconFromGlyph(QStringLiteral("skip_previous"));
    m_icons[1] = iconFromGlyph(QStringLiteral("play_arrow"));
    m_icons[2] = iconFromGlyph(QStringLiteral("pause"));
    m_icons[3] = iconFromGlyph(QStringLiteral("skip_next"));
    qApp->installNativeEventFilter(this);
#endif
}

Taskbar::~Taskbar()
{
#ifdef Q_OS_WIN
    qApp->removeNativeEventFilter(this);
    if (m_taskbar) static_cast<ITaskbarList3 *>(m_taskbar)->Release();
    for (void *i : m_icons) if (i) DestroyIcon(static_cast<HICON>(i));
#endif
}

void Taskbar::setWindow(QWindow *window) { m_window = window; }

void Taskbar::ensureButtons()
{
#ifdef Q_OS_WIN
    if (m_buttonsAdded || !m_taskbar || !m_window) return;
    THUMBBUTTON b[3] = {};
    const int ids[3] = {IdPrevious, IdPlayPause, IdNext};
    const wchar_t *tips[3] = {L"Previous", L"Play", L"Next"};
    for (int i = 0; i < 3; ++i) {
        b[i].dwMask = THUMBBUTTONMASK(THB_ICON | THB_TOOLTIP | THB_FLAGS);
        b[i].iId = UINT(ids[i]);
        b[i].hIcon = static_cast<HICON>(m_icons[i == 0 ? 0 : i == 1 ? 1 : 3]);
        wcsncpy(b[i].szTip, tips[i], 259);
        b[i].dwFlags = THBF_ENABLED;
    }
    auto hwnd = reinterpret_cast<HWND>(m_window->winId());
    m_buttonsAdded = SUCCEEDED(static_cast<ITaskbarList3 *>(m_taskbar)->ThumbBarAddButtons(hwnd, 3, b));
#endif
}

void Taskbar::update(bool active, bool playing, double fraction, bool hasPrevious, bool hasNext)
{
#ifdef Q_OS_WIN
    if (!m_taskbar || !m_window) return;
    auto *tb = static_cast<ITaskbarList3 *>(m_taskbar);
    auto hwnd = reinterpret_cast<HWND>(m_window->winId());
    ensureButtons();
    if (!active) {
        tb->SetProgressState(hwnd, TBPF_NOPROGRESS);
    } else {
        tb->SetProgressState(hwnd, playing ? TBPF_NORMAL : TBPF_PAUSED);
        tb->SetProgressValue(hwnd, ULONGLONG(std::clamp(fraction, 0.0, 1.0) * 1000), 1000);
    }
    if (m_buttonsAdded && (playing != m_playing || hasPrevious != m_hasPrevious || hasNext != m_hasNext)) {
        THUMBBUTTON b[3] = {};
        const int ids[3] = {IdPrevious, IdPlayPause, IdNext};
        const bool enabled[3] = {active && hasPrevious, active, active && hasNext};
        for (int i = 0; i < 3; ++i) {
            b[i].dwMask = THUMBBUTTONMASK(THB_ICON | THB_TOOLTIP | THB_FLAGS);
            b[i].iId = UINT(ids[i]);
            b[i].hIcon = static_cast<HICON>(m_icons[i == 0 ? 0 : i == 1 ? (playing ? 2 : 1) : 3]);
            wcsncpy(b[i].szTip, i == 0 ? L"Previous" : i == 2 ? L"Next" : (playing ? L"Pause" : L"Play"), 259);
            b[i].dwFlags = enabled[i] ? THBF_ENABLED : THBF_DISABLED;
        }
        tb->ThumbBarUpdateButtons(hwnd, 3, b);
    }
#else
    Q_UNUSED(active) Q_UNUSED(fraction)
#endif
    m_playing = playing;
    m_hasPrevious = hasPrevious;
    m_hasNext = hasNext;
}

bool Taskbar::nativeEventFilter(const QByteArray &eventType, void *message, qintptr *result)
{
#ifdef Q_OS_WIN
    Q_UNUSED(result)
    if (eventType != "windows_generic_MSG") return false;
    const MSG *msg = static_cast<MSG *>(message);
    if (msg->message == m_buttonCreatedMsg) {
        // Explorer restarted or the button was (re)created: buttons must be added again
        m_buttonsAdded = false;
        ensureButtons();
    } else if (msg->message == WM_COMMAND && HIWORD(msg->wParam) == THBN_CLICKED) {
        switch (LOWORD(msg->wParam)) {
        case IdPrevious: emit previousClicked(); return true;
        case IdPlayPause: emit playPauseClicked(); return true;
        case IdNext: emit nextClicked(); return true;
        }
    }
#else
    Q_UNUSED(eventType) Q_UNUSED(message) Q_UNUSED(result)
#endif
    return false;
}
