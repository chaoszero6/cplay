#pragma once

#include <QObject>
#include <QPoint>
#include <QPointer>
#include <QRect>
#include <QSettings>
#include <QSize>
#include <QWindow>

/**
 * Moves and sizes the frameless player window ("windowCtl" in QML):
 * drag by the title bar or the video (works while "always on top" is on),
 * magnetic screen edges and corners, maximize that keeps the taskbar visible,
 * dragging out of maximized, size presets and remembered geometry.
 */
class WindowController : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool maximized READ maximized NOTIFY maximizedChanged)
    Q_PROPERTY(bool magnetic READ magnetic WRITE setMagnetic NOTIFY magneticChanged)
    Q_PROPERTY(bool dragging READ dragging NOTIFY draggingChanged)
    // "Window size on play": "keep" (default), "custom" or "fit"
    Q_PROPERTY(QString sizeMode READ sizeMode WRITE setSizeMode NOTIFY sizeSettingsChanged)
    Q_PROPERTY(QSize customSize READ customSize NOTIFY sizeSettingsChanged)
    Q_PROPERTY(QString customSizeText READ customSizeText NOTIFY sizeSettingsChanged)
    Q_PROPERTY(double fitScale READ fitScale WRITE setFitScale NOTIFY sizeSettingsChanged)

public:
    explicit WindowController(const QString &settingsFile, QObject *parent = nullptr);

    void setWindow(QWindow *window);

    bool maximized() const { return m_maximized; }
    bool magnetic() const { return m_magnetic; }
    void setMagnetic(bool on);
    bool dragging() const { return m_dragging; }
    QString sizeMode() const { return m_sizeMode; }
    void setSizeMode(const QString &mode);
    QSize customSize() const { return m_customSize; }
    QString customSizeText() const;
    double fitScale() const { return m_fitScale; }
    void setFitScale(double scale);

    /** Called when a new video's size is known: applies the "Window size on play" setting. */
    Q_INVOKABLE void applySizeOnPlay(int videoWidth, int videoHeight, int chromeWidth, int chromeHeight);
    /** "Use current window size as my size": switches the mode to custom. */
    Q_INVOKABLE void useCurrentSizeAsCustom();
    /** "1280x720" from the size dialog; false if it is not a size. */
    Q_INVOKABLE bool setCustomSizeText(const QString &text);

    /** Call on mouse press in a drag area, then dragMove() on every move. */
    Q_INVOKABLE void startDrag();
    Q_INVOKABLE void dragMove();
    Q_INVOKABLE void endDrag();
    Q_INVOKABLE void toggleMaximize();
    Q_INVOKABLE void minimize();
    /** Window size so the video shows at [scale] x its size (50 %, 100 %, 200 %...). */
    Q_INVOKABLE void fitVideo(double scale, int videoWidth, int videoHeight, int chromeWidth, int chromeHeight);
    Q_INVOKABLE void center();
    /** Mini player: small window in the bottom-right corner; false restores the old size and place. */
    Q_INVOKABLE void setMini(bool on);
    Q_INVOKABLE void restoreGeometry();
    Q_INVOKABLE void saveGeometry();

signals:
    void maximizedChanged();
    void magneticChanged();
    void draggingChanged();
    void sizeSettingsChanged();

private:
    QRect workAreaFor(const QRect &r) const;
    void setMaximized(bool m);

    QSettings m_settings;
    QPointer<QWindow> m_window;
    QPoint m_grabOffset;       // cursor minus window top-left at press
    QRect m_normalGeometry;    // before maximizing
    QRect m_beforeMini;        // before the mini player
    bool m_maximized = false;
    bool m_magnetic = true;
    bool m_dragging = false;
    bool m_pendingRestore = false;
    double m_grabRelX = 0.5;
    QString m_sizeMode = QStringLiteral("keep");
    QSize m_customSize;
    double m_fitScale = 1.0;
};
