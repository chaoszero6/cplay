#include "core/WindowSnap.h"
#include <QTest>

// Screen work area 1920x1040 (taskbar takes the bottom 40 px)
static const QRect area(0, 0, 1920, 1040);

class WindowSnapTest : public QObject
{
    Q_OBJECT
private slots:
    void farFromEdgesDoesNotMove()
    {
        QCOMPARE(WindowSnap::snap({500, 300, 800, 450}, area), QPoint(500, 300));
    }
    void closeToLeftAndTopSticks()
    {
        QCOMPARE(WindowSnap::snap({12, 300, 800, 450}, area), QPoint(0, 300));
        QCOMPARE(WindowSnap::snap({500, -9, 800, 450}, area), QPoint(500, 0));
    }
    void closeToRightAndBottomSticks()
    {
        // right edge at 1920 - 10: sticks to 1920
        QCOMPARE(WindowSnap::snap({1110, 300, 800, 450}, area), QPoint(1120, 300));
        // bottom edge 15 px under the work area (over the taskbar): pulled back up
        QCOMPARE(WindowSnap::snap({500, 605, 800, 450}, area), QPoint(500, 590));
    }
    void nearACornerDocksIntoIt()
    {
        QCOMPARE(WindowSnap::snap({1125, 595, 800, 450}, area), QPoint(1120, 590));
        QCOMPARE(WindowSnap::snap({-5, 6, 800, 450}, area), QPoint(0, 0));
    }
    void thresholdIsInclusiveAndConfigurable()
    {
        QCOMPARE(WindowSnap::snap({18, 300, 800, 450}, area), QPoint(0, 300));
        QCOMPARE(WindowSnap::snap({19, 300, 800, 450}, area), QPoint(19, 300));
        QCOMPARE(WindowSnap::snap({30, 300, 800, 450}, area, 40), QPoint(0, 300));
    }
    void secondMonitorUsesItsOwnEdges()
    {
        const QRect right(1920, 0, 2560, 1400);
        QCOMPARE(WindowSnap::snap({1930, 10, 800, 450}, right), QPoint(1920, 0));
        const QList<QRect> areas{area, right};
        QCOMPARE(WindowSnap::areaFor({1800, 100, 800, 450}, areas), right); // mostly on the right screen
        QCOMPARE(WindowSnap::areaFor({1500, 100, 800, 450}, areas), area);
        QCOMPARE(WindowSnap::areaFor({-5000, -5000, 10, 10}, areas), area); // off-screen: first
    }
    void draggingOutOfMaximizedKeepsTheGrabPoint()
    {
        // Grabbed at 75 % of the width, 12 px from the top
        const QRect r = WindowSnap::restoreUnderCursor({800, 450}, {1500, 12}, 0.75, 12, area);
        QCOMPARE(r, QRect(900, 0, 800, 450));
        // Near the left edge: stays on screen
        QCOMPARE(WindowSnap::restoreUnderCursor({800, 450}, {10, 12}, 0.75, 12, area).x(), 0);
    }
    void offScreenWindowIsBroughtBack()
    {
        QCOMPARE(WindowSnap::keepOnScreen({3000, 2000, 800, 450}, area), QRect(1120, 590, 800, 450));
        QCOMPARE(WindowSnap::keepOnScreen({100, 100, 4000, 3000}, area), QRect(0, 0, 1920, 1040));
        QCOMPARE(WindowSnap::keepOnScreen({100, 100, 800, 450}, area), QRect(100, 100, 800, 450));
    }
};

QTEST_GUILESS_MAIN(WindowSnapTest)
#include "WindowSnapTest.moc"
