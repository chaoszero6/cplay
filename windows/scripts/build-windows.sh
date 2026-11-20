#!/usr/bin/env bash
# Cross-builds CPlay.exe for Windows x64 from Linux, then deploys it into
# build-windows/deploy/CPlay (all DLLs, Qt plugins and QML modules).
set -euo pipefail
cd "$(dirname "$0")/.."
export QT_WIN=${QT_WIN:-/opt/qt/6.8.2/llvm-mingw_64}
QT_HOST=${QT_HOST:-/opt/qt/6.8.2/gcc_64}
[[ -f third_party/mpv-win64/libmpv-2.dll && -f third_party/vulkan-loader/vulkan-1.dll ]] || scripts/fetch-third-party.sh
[[ -f third_party/net-windows/lib/libsmb2.a && -f third_party/net-windows/lib/libnfs.a ]] || scripts/build-net-libs.sh windows
cmake -S . -B build-windows -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$PWD/cmake/llvm-mingw-toolchain.cmake" \
    -DCMAKE_PREFIX_PATH="$QT_WIN" \
    -DQT_HOST_PATH="$QT_HOST" \
    -DBUILD_TESTING=OFF
cmake --build build-windows --parallel --target chaosplay
scripts/deploy-windows.sh
