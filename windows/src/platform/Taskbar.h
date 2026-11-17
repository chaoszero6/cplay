#pragma once
#include <QAbstractNativeEventFilter>
#include <QObject>
#include <QPointer>
#include <QWindow>

/**
 * Windows taskbar button (spec: "thumbnail toolbar with play/pause, progress
 * in the taskbar button"): green progress while playing, yellow when paused,
 * and previous / play-pause / next buttons in the thumbnail. No-op elsewhere.
 */
class Taskbar : public QObject, public QAbstractNativeEventFilter
{
    Q_OBJECT
public:
    explicit Taskbar(QObject *parent = nullptr);
    ~Taskbar() override;
    void setWindow(QWindow *window);

    /** fraction 0..1; active=false clears the progress. */
    Q_INVOKABLE void update(bool active, bool playing, double fraction, bool hasPrevious, bool hasNext);

    bool nativeEventFilter(const QByteArray &eventType, void *message, qintptr *result) override;

signals:
    void playPauseClicked();
    void previousClicked();
    void nextClicked();

private:
    void ensureButtons();
    QPointer<QWindow> m_window;
    void *m_taskbar = nullptr;   // ITaskbarList3*
    unsigned m_buttonCreatedMsg = 0;
    bool m_buttonsAdded = false;
    bool m_playing = false;
    bool m_hasPrevious = false;
    bool m_hasNext = false;
    void *m_icons[4] = {};       // HICON: previous, play, pause, next
};
