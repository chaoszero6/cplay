#include "app/AppController.h"
#include "app/WindowController.h"
#include "app/PlayerTools.h"
#include "player/PreviewProvider.h"
#include "library/LibraryController.h"
#include "library/ThumbnailService.h"
#include "platform/SingleInstance.h"
#include "platform/Taskbar.h"

#include <QSettings>

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

    // One window: a second launch hands its files to the running one (setting, default on)
    const QString dataDir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    const bool single = QSettings(dataDir + QStringLiteral("/settings.ini"), QSettings::IniFormat)
                            .value(QStringLiteral("player/singleInstance"), true).toBool();
    QString user = qEnvironmentVariable("USERNAME");
    if (user.isEmpty()) user = qEnvironmentVariable("USER");
    SingleInstance instance(QStringLiteral("CPlay-") + user);
    if (single && instance.forwardToRunning(files)) return 0;
    if (single) instance.listen();

    AppController controller(files);
    QObject::connect(&instance, &SingleInstance::filesReceived, &controller, &AppController::openRequested);
    WindowController windowCtl(QStandardPaths::writableLocation(QStandardPaths::AppDataLocation) + QStringLiteral("/settings.ini"));
    // Objects QML uses must outlive the engine: declared before it, destroyed after it
    PlayerTools tools(dataDir);
    Taskbar taskbar;
    const QString cacheDir = QStandardPaths::writableLocation(QStandardPaths::CacheLocation);
    auto thumbs = std::make_shared<ThumbnailService>(cacheDir + QStringLiteral("/thumbs"), 3);
    LibraryController library(dataDir, controller.resumeStore(), thumbs);
    QObject::connect(&controller, &AppController::recentChanged, &library, &LibraryController::refreshProgress);
    QQmlApplicationEngine engine;
    engine.rootContext()->setContextProperty(QStringLiteral("app"), &controller);
    engine.rootContext()->setContextProperty(QStringLiteral("windowCtl"), &windowCtl);
    engine.rootContext()->setContextProperty(QStringLiteral("tools"), &tools);
    engine.addImageProvider(QStringLiteral("preview"), new PreviewProvider); // engine takes ownership
    engine.rootContext()->setContextProperty(QStringLiteral("taskbar"), &taskbar);
    engine.rootContext()->setContextProperty(QStringLiteral("library"), &library);
    engine.addImageProvider(QStringLiteral("thumb"), new ThumbnailProvider(thumbs));
    QObject::connect(&engine, &QQmlApplicationEngine::objectCreationFailed, &app,
                     [] { QCoreApplication::exit(1); }, Qt::QueuedConnection);
    engine.loadFromModule("ChaosPlay", "Main");
    if (engine.rootObjects().isEmpty()) return 1;
    auto *window = qobject_cast<QQuickWindow *>(engine.rootObjects().constFirst());
    controller.setWindow(window);
    windowCtl.setWindow(window);
    windowCtl.restoreGeometry(); // after the window applied its default size
    taskbar.setWindow(window);

    // Developer hook for automated UI checks: CHAOSPLAY_SNAPSHOT=out.png [CHAOSPLAY_SNAPSHOT_MS=2500]
    // saves a picture of the window and quits. Not used in normal runs.
    const QString snapshot = qEnvironmentVariable("CHAOSPLAY_SNAPSHOT");
    if (!snapshot.isEmpty() && window) {
        const int delay = qEnvironmentVariableIntValue("CHAOSPLAY_SNAPSHOT_MS");
        QTimer::singleShot(delay > 0 ? delay : 2500, window, [window, snapshot] {
            const QString script = qEnvironmentVariable("CHAOSPLAY_SNAPSHOT_JS");
            if (!script.isEmpty()) QMetaObject::invokeMethod(window, "runDevScript", Q_ARG(QVariant, script));
            // Optional second script just before the picture (to check results of the first)
            const QString later = qEnvironmentVariable("CHAOSPLAY_SNAPSHOT_JS_LATE");
            const int after = qEnvironmentVariableIsSet("CHAOSPLAY_SNAPSHOT_AFTER_JS_MS")
                                  ? qEnvironmentVariableIntValue("CHAOSPLAY_SNAPSHOT_AFTER_JS_MS") : 900;
            QTimer::singleShot(script.isEmpty() ? 0 : after, window, [window, snapshot, later] {
                if (!later.isEmpty()) QMetaObject::invokeMethod(window, "runDevScript", Q_ARG(QVariant, later));
                if (window->isVisible()) window->grabWindow().save(snapshot);
                QCoreApplication::quit();
            });
        });
    }
    return app.exec();
}
