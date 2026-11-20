cmake_minimum_required(VERSION 3.21)

# CPlay for Windows (also builds on Linux for testing). Internal project/module name: ChaosPlay.
# C++ / Qt 6 Quick / libmpv, per docs/spec/01-feature-and-build-guide.md section 8.
project(ChaosPlay VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)
set(CMAKE_AUTORCC ON)

find_package(Qt6 6.8 REQUIRED COMPONENTS Core Gui Network Qml Quick QuickControls2 OpenGL Test)
qt_standard_project_setup(REQUIRES 6.8)

# ---- libmpv -------------------------------------------------------------
# Windows: shinchiro's mpv-dev package in third_party/mpv-win64 (libmpv-2.dll).
# Linux: system libmpv.so.2 with headers from MPV_ROOT (see scripts/build-linux.sh).
if(WIN32)
    set(MPV_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/mpv-win64" CACHE PATH "libmpv dev package")
    add_library(mpv SHARED IMPORTED GLOBAL)
    set_target_properties(mpv PROPERTIES
        IMPORTED_IMPLIB "${MPV_ROOT}/libmpv.dll.a"
        IMPORTED_LOCATION "${MPV_ROOT}/libmpv-2.dll"
        INTERFACE_INCLUDE_DIRECTORIES "${MPV_ROOT}/include")
else()
    set(MPV_ROOT "/opt/mpv-dev/usr" CACHE PATH "libmpv headers + libmpv.so")
    find_library(MPV_LIBRARY NAMES mpv HINTS "${MPV_ROOT}/lib/x86_64-linux-gnu" REQUIRED)
    add_library(mpv SHARED IMPORTED GLOBAL)
    set_target_properties(mpv PROPERTIES
        IMPORTED_LOCATION "${MPV_LIBRARY}"
        INTERFACE_INCLUDE_DIRECTORIES "${MPV_ROOT}/include")
endif()

# ---- share clients: libsmb2 + libnfs, built by scripts/build-net-libs.sh ------
if(WIN32)
    set(NET_LIBS_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/net-windows" CACHE PATH "libsmb2/libnfs build")
else()
    set(NET_LIBS_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/net-linux" CACHE PATH "libsmb2/libnfs build")
endif()
add_library(netlibs INTERFACE)
target_include_directories(netlibs INTERFACE "${NET_LIBS_ROOT}/include")
target_link_libraries(netlibs INTERFACE "${NET_LIBS_ROOT}/lib/libsmb2.a" "${NET_LIBS_ROOT}/lib/libnfs.a")
if(WIN32)
    target_link_libraries(netlibs INTERFACE ws2_32 iphlpapi crypt32)
endif()

# ---- network: servers, share clients, local streaming server --------------
add_library(chaosplay_net STATIC
    src/network/FtpClient.h src/network/FtpClient.cpp
    src/network/LocalMediaServer.h src/network/LocalMediaServer.cpp
    src/network/ShareFs.h src/network/ShareFs.cpp
    src/network/SecretStore.h src/network/SecretStore.cpp
    src/network/NetworkController.h src/network/NetworkController.cpp
    src/tv/TvController.h src/tv/TvController.cpp
    src/player/LocationResolver.h src/player/LocationResolver.cpp
)
target_link_libraries(chaosplay_net PUBLIC chaosplay_core netlibs Qt6::Core Qt6::Network)

# ---- core: rules and stores without any UI (unit tested) -----------------
add_library(chaosplay_core STATIC
    src/core/TimeFormat.h src/core/TimeFormat.cpp
    src/core/PlaybackRules.h src/core/PlaybackRules.cpp
    src/core/ResumeStore.h src/core/ResumeStore.cpp
    src/core/MediaFiles.h src/core/MediaFiles.cpp
    src/core/WindowSnap.h src/core/WindowSnap.cpp
    src/core/WindowSizeRules.h src/core/WindowSizeRules.cpp
    src/core/Bookmarks.h src/core/Bookmarks.cpp
    src/core/SleepTimer.h src/core/SleepTimer.cpp
    src/core/Playlists.h src/core/Playlists.cpp
    src/core/Adjustments.h src/core/Adjustments.cpp
    src/core/LibraryRules.h src/core/LibraryRules.cpp
    src/core/NetRules.h src/core/NetRules.cpp
    src/core/CastRules.h src/core/CastRules.cpp
)
target_include_directories(chaosplay_core PUBLIC src)
target_link_libraries(chaosplay_core PUBLIC Qt6::Core)

# ---- app -------------------------------------------------------------------
qt_add_executable(chaosplay WIN32
    src/main.cpp
    src/player/MpvPlayer.h src/player/MpvPlayer.cpp
    src/player/SelfTest.h src/player/SelfTest.cpp
    src/app/AppController.h src/app/AppController.cpp
    src/app/WindowController.h src/app/WindowController.cpp
    src/app/PlayerTools.h src/app/PlayerTools.cpp
    src/player/FramePreviewer.h src/player/FramePreviewer.cpp
    src/player/PreviewProvider.h src/player/PreviewProvider.cpp
    src/platform/Platform.h src/platform/Platform.cpp
    src/platform/SingleInstance.h src/platform/SingleInstance.cpp
    src/platform/Taskbar.h src/platform/Taskbar.cpp
    src/library/ThumbnailService.h src/library/ThumbnailService.cpp
    src/library/LibraryController.h src/library/LibraryController.cpp
)
if(WIN32)
    target_sources(chaosplay PRIVATE assets/chaosplay.rc) # exe icon
endif()
set_target_properties(chaosplay PROPERTIES OUTPUT_NAME "CPlay" RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin")

set_source_files_properties(qml/Theme.qml PROPERTIES QT_QML_SINGLETON_TYPE TRUE)
qt_add_qml_module(chaosplay
    URI ChaosPlay
    VERSION 1.0
    QML_FILES
        qml/Main.qml
        qml/Theme.qml
        qml/PlayerPage.qml
        qml/TitleBar.qml
        qml/ControlBar.qml
        qml/ResizeGrips.qml
        qml/SeekBar.qml
        qml/IconButton.qml
        qml/Icon.qml
        qml/ChoicePopup.qml
        qml/ChaosMenu.qml
        qml/ChaosMenuItem.qml
        qml/Osd.qml
        qml/ControlBox.qml
        qml/PlaylistPanel.qml
        qml/LockOverlay.qml
        qml/HomeScreen.qml
        qml/LibraryPage.qml
        qml/NetworkPage.qml
        qml/SettingsPage.qml
        qml/VideoCard.qml
        qml/VideoRow.qml
        qml/PageButton.qml
        qml/PageIconButton.qml
        qml/PageField.qml
        qml/PageSwitch.qml
        qml/Chip.qml
        qml/ServerDialog.qml
        qml/TvPicker.qml
        qml/CastOverlay.qml
    RESOURCES
        assets/fonts/MaterialIcons-Regular.ttf
        assets/chaosplay-256.png
)
# Scan only qml/ for imports (never the build or Wine folders next to it)
set_target_properties(chaosplay PROPERTIES QT_QML_ROOT_PATH "${CMAKE_CURRENT_SOURCE_DIR}/qml")
target_include_directories(chaosplay PRIVATE src src/player)
target_compile_definitions(chaosplay PRIVATE CHAOSPLAY_VERSION="${PROJECT_VERSION}")
target_link_libraries(chaosplay PRIVATE
    chaosplay_core chaosplay_net mpv
    Qt6::Gui Qt6::Network Qt6::Qml Qt6::Quick Qt6::QuickControls2 Qt6::OpenGL)
if(WIN32)
    target_link_libraries(chaosplay PRIVATE dwmapi ole32)
endif()

# ---- tests -----------------------------------------------------------------
enable_testing()
foreach(t TimeFormatTest PlaybackRulesTest ResumeStoreTest MediaFilesTest WindowSnapTest WindowSizeRulesTest BookmarksTest SleepTimerTest PlaylistsTest AdjustmentsTest LibraryRulesTest NetRulesTest CastRulesTest)
    qt_add_executable(${t} tests/core/${t}.cpp)
    target_link_libraries(${t} PRIVATE chaosplay_core Qt6::Test)
    add_test(NAME ${t} COMMAND ${t})
endforeach()

target_sources(NetRulesTest PRIVATE tests/core/NetRulesTestData.cpp)

# Plays the bundled test clips through libmpv with no window (vo=null, ao=null)
qt_add_executable(MpvPlaybackTest tests/player/MpvPlaybackTest.cpp src/player/SelfTest.cpp)
target_include_directories(MpvPlaybackTest PRIVATE src)
target_link_libraries(MpvPlaybackTest PRIVATE mpv Qt6::Test Qt6::Core)
target_compile_definitions(MpvPlaybackTest PRIVATE
    TEST_MEDIA_DIR="${CMAKE_CURRENT_SOURCE_DIR}/../tools/test-media")
add_test(NAME MpvPlaybackTest COMMAND MpvPlaybackTest)

# Seek-bar preview frames (hidden libmpv instance, software rendering)
qt_add_executable(FramePreviewerTest tests/player/FramePreviewerTest.cpp src/player/FramePreviewer.cpp src/player/LocationResolver.cpp)
target_include_directories(FramePreviewerTest PRIVATE src)
target_link_libraries(FramePreviewerTest PRIVATE chaosplay_core mpv Qt6::Test Qt6::Core Qt6::Gui)
target_compile_definitions(FramePreviewerTest PRIVATE
    TEST_MEDIA_DIR="${CMAKE_CURRENT_SOURCE_DIR}/../tools/test-media")
add_test(NAME FramePreviewerTest COMMAND FramePreviewerTest)

# Network sources end to end against tools/test-servers (skipped when they are not running)
qt_add_executable(NetworkIntegrationTest tests/network/NetworkIntegrationTest.cpp)
target_link_libraries(NetworkIntegrationTest PRIVATE chaosplay_net Qt6::Test Qt6::Network)
target_compile_definitions(NetworkIntegrationTest PRIVATE
    TEST_SERVERS_DIR="${CMAKE_CURRENT_SOURCE_DIR}/../tools/test-servers")
add_test(NAME NetworkIntegrationTest COMMAND NetworkIntegrationTest)

# Play on TV against tools/test-servers/mocktv.py and mockcast.py (skipped when not running)
qt_add_executable(TvIntegrationTest tests/network/TvIntegrationTest.cpp)
target_link_libraries(TvIntegrationTest PRIVATE chaosplay_net Qt6::Test Qt6::Network)
target_compile_definitions(TvIntegrationTest PRIVATE
    TEST_SERVERS_DIR="${CMAKE_CURRENT_SOURCE_DIR}/../tools/test-servers")
add_test(NAME TvIntegrationTest COMMAND TvIntegrationTest)
