#!/usr/bin/env bash
# Linux build of ChaosPlay (same code as Windows) for development and tests.
# Usage: scripts/build-linux.sh [--test]
set -euo pipefail
cd "$(dirname "$0")/.."
QT=${QT:-/opt/qt/6.8.2/gcc_64}
cmake -S . -B build-linux -G Ninja \
    -DCMAKE_BUILD_TYPE=RelWithDebInfo \
    -DCMAKE_PREFIX_PATH="$QT" \
    -DMPV_ROOT=/opt/mpv-dev/usr \
    -DOPENGL_INCLUDE_DIR=/opt/gl-dev/usr/include \
    -DOPENGL_opengl_LIBRARY=/opt/gl-dev/usr/lib/x86_64-linux-gnu/libOpenGL.so \
    -DOPENGL_glx_LIBRARY=/opt/gl-dev/usr/lib/x86_64-linux-gnu/libGLX.so \
    -DOPENGL_gl_LIBRARY=/opt/gl-dev/usr/lib/x86_64-linux-gnu/libGL.so \
    -DOPENGL_egl_LIBRARY=/opt/gl-dev/usr/lib/x86_64-linux-gnu/libEGL.so \
    -DOPENGL_EGL_INCLUDE_DIR=/opt/gl-dev/usr/include \
    -DOPENGL_GLX_INCLUDE_DIR=/opt/gl-dev/usr/include
cmake --build build-linux --parallel
if [[ "${1:-}" == "--test" ]]; then
    QT_QPA_PLATFORM=offscreen ctest --test-dir build-linux --output-on-failure
fi
