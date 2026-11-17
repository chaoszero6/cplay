cmake_minimum_required(VERSION 3.21)

# CPlay for Windows (also builds on Linux for testing). Internal project/module name: ChaosPlay.
# C++ / Qt 6 Quick / libmpv, per docs/spec/01-feature-and-build-guide.md section 8.
project(ChaosPlay VERSION 0.2.2 LANGUAGES CXX)

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

# ---- core: rules and stores without any UI (unit tested) -----------------
add_library(chaosplay_core STATIC
    src/core/TimeFormat.h src/core/TimeFormat.cpp
    src/core/PlaybackRules.h src/core/PlaybackRules.cpp
    src/core/ResumeStore.h src/core/ResumeStore.cpp
    src/core/MediaFiles.h src/core/MediaFiles.cpp
    src/core/WindowSnap.h src/core/WindowSnap.cpp
    src/core/WindowSizeRules.h src/core/WindowSizeRules.cpp
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
    src/platform/Platform.h src/platform/Platform.cpp
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
        qml/IdleScreen.qml
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
    RESOURCES
        assets/fonts/MaterialIcons-Regular.ttf
        assets/chaosplay-256.png
)
# Scan only qml/ for imports (never the build or Wine folders next to it)
set_target_properties(chaosplay PROPERTIES QT_QML_ROOT_PATH "${CMAKE_CURRENT_SOURCE_DIR}/qml")
target_include_directories(chaosplay PRIVATE src src/player)
target_compile_definitions(chaosplay PRIVATE CHAOSPLAY_VERSION="${PROJECT_VERSION}")
target_link_libraries(chaosplay PRIVATE
    chaosplay_core mpv
    Qt6::Gui Qt6::Network Qt6::Qml Qt6::Quick Qt6::QuickControls2 Qt6::OpenGL)
if(WIN32)
    target_link_libraries(chaosplay PRIVATE dwmapi)
endif()

# ---- tests -----------------------------------------------------------------
enable_testing()
foreach(t TimeFormatTest PlaybackRulesTest ResumeStoreTest MediaFilesTest WindowSnapTest WindowSizeRulesTest)
    qt_add_executable(${t} tests/core/${t}.cpp)
    target_link_libraries(${t} PRIVATE chaosplay_core Qt6::Test)
    add_test(NAME ${t} COMMAND ${t})
endforeach()

# Plays the bundled test clips through libmpv with no window (vo=null, ao=null)
qt_add_executable(MpvPlaybackTest tests/player/MpvPlaybackTest.cpp src/player/SelfTest.cpp)
target_include_directories(MpvPlaybackTest PRIVATE src)
target_link_libraries(MpvPlaybackTest PRIVATE mpv Qt6::Test Qt6::Core)
target_compile_definitions(MpvPlaybackTest PRIVATE
    TEST_MEDIA_DIR="${CMAKE_CURRENT_SOURCE_DIR}/../tools/test-media")
add_test(NAME MpvPlaybackTest COMMAND MpvPlaybackTest)
