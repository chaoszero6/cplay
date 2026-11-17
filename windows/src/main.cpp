#include "app/AppController.h"
#include "app/WindowController.h"

#include <QStandardPaths>
#include "player/SelfTest.h"

#include <QFileInfo>
#include <QFontDatabase>
#include <QGuiApplication>
#include <QIcon>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickStyle>
#include <QQuickWindow>
#include <QTextStream>
#include <QTimer>
#include <clocale>
#include <cstdio>

#ifdef Q_OS_WIN
#include <windows.h>
#endif

static int runSelfTest(const QStringList &args)
{
#ifdef Q_OS_WIN
    // GUI-subsystem exe: attach to the calling console so the report is visible
    if (AttachConsole(ATTACH_PARENT_PROCESS)) {
        std::freopen("CONOUT$", "w", stdout);
        std::freopen("CONOUT$", "w", stderr);
    }
#endif
    QTextStream out(stdout);
    const int i = args.indexOf(QStringLiteral("--self-test"));
    const QString file = args.value(i + 1);
    if (file.isEmpty()) {
        out << "usage: CPlay --self-test <video file>\n";
        return 2;
    }
    const SelfTest::Result r = SelfTest::run(file);
    out << r.log << "\n" << (r.ok ? "SELF-TEST OK" : "SELF-TEST FAILED") << "\n";
    out.flush();
    return r.ok ? 0 : 1;
}

int main(int argc, char *argv[])
{
    QCoreApplication::setOrganizationName(QStringLiteral("ChaosWares"));
    // Internal name stays "ChaosPlay": it names the settings/history folder in %APPDATA%
    QCoreApplication::setApplicationName(QStringLiteral("ChaosPlay"));
    QGuiApplication::setApplicationDisplayName(QStringLiteral("CPlay"));
    QCoreApplication::setApplicationVersion(QStringLiteral(CHAOSPLAY_VERSION));

    // PCs without a usable OpenGL driver (VMs, remote desktop): Qt ships Mesa
    // as opengl32sw.dll; "--software-opengl" switches to it
    for (int i = 1; i < argc; ++i)
        if (qstrcmp(argv[i], "--software-opengl") == 0) QCoreApplication::setAttribute(Qt::AA_UseSoftwareOpenGL);

    // libmpv needs the C numeric locale
    QGuiApplication app(argc, argv);
    std::setlocale(LC_NUMERIC, "C");

    const QStringList args = app.arguments();
    if (args.contains(QStringLiteral("--self-test"))) return runSelfTest(args);

    // mpv renders with OpenGL; Qt would pick Direct3D 11 on Windows otherwise
    QQuickWindow::setGraphicsApi(QSGRendererInterface::OpenGL);
    QQuickStyle::setStyle(QStringLiteral("Basic"));
    app.setWindowIcon(QIcon(QStringLiteral(":/qt/qml/ChaosPlay/assets/chaosplay-256.png")));
    if (QFontDatabase::addApplicationFont(QStringLiteral(":/qt/qml/ChaosPlay/assets/fonts/MaterialIcons-Regular.ttf")) < 0)
        qWarning("CPlay: icon font missing");

    // Files from "Open with" / drag onto the exe / command line
    QStringList files;
    for (int i = 1; i < args.size(); ++i) {
        const QString a = args.at(i);
        if (a.startsWith(QLatin1String("--"))) continue;
        files << (QFileInfo::exists(a) ? QFileInfo(a).absoluteFilePath() : a);
    }

    AppController controller(files);
    WindowController windowCtl(QStandardPaths::writableLocation(QStandardPaths::AppDataLocation) + QStringLiteral("/settings.ini"));
    QQmlApplicationEngine engine;
    engine.rootContext()->setContextProperty(QStringLiteral("app"), &controller);
    engine.rootContext()->setContextProperty(QStringLiteral("windowCtl"), &windowCtl);
    QObject::connect(&engine, &QQmlApplicationEngine::objectCreationFailed, &app,
                     [] { QCoreApplication::exit(1); }, Qt::QueuedConnection);
    engine.loadFromModule("ChaosPlay", "Main");
    if (engine.rootObjects().isEmpty()) return 1;
    auto *window = qobject_cast<QQuickWindow *>(engine.rootObjects().constFirst());
    controller.setWindow(window);
    windowCtl.setWindow(window);
    windowCtl.restoreGeometry(); // after the window applied its default size

    // Developer hook for automated UI checks: CHAOSPLAY_SNAPSHOT=out.png [CHAOSPLAY_SNAPSHOT_MS=2500]
    // saves a picture of the window and quits. Not used in normal runs.
    const QString snapshot = qEnvironmentVariable("CHAOSPLAY_SNAPSHOT");
    if (!snapshot.isEmpty() && window) {
        const int delay = qEnvironmentVariableIntValue("CHAOSPLAY_SNAPSHOT_MS");
        QTimer::singleShot(delay > 0 ? delay : 2500, window, [window, snapshot] {
            const QString script = qEnvironmentVariable("CHAOSPLAY_SNAPSHOT_JS");
            if (!script.isEmpty()) QMetaObject::invokeMethod(window, "runDevScript", Q_ARG(QVariant, script));
            QTimer::singleShot(script.isEmpty() ? 0 : 900, window, [window, snapshot] {
                window->grabWindow().save(snapshot);
                QCoreApplication::quit();
            });
        });
    }
    return app.exec();
}
