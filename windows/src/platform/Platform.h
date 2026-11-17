#pragma once
class QWindow;

/** Small OS integrations; no-ops where the OS has no equivalent. */
namespace Platform {
/** Keeps the window above all other windows (the player's pin button). */
void setAlwaysOnTop(QWindow *window, bool onTop);
/** Stops the screen saver / sleep while a video plays (spec 8.2). */
void keepAwake(bool awake);
/** Windows 10/11 dark title bar to match the dark theme. */
void useDarkTitleBar(QWindow *window, bool dark);
}
