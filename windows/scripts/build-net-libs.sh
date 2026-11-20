#!/usr/bin/env bash
# Builds the network share libraries CPlay links statically (LGPL-2.1, sources pinned by SHA-256):
#   libsmb2 6.0.0  (SMB2/3 client)   libnfs 6.0.2 (NFS v3/v4 client)
# usage: scripts/build-net-libs.sh linux|windows   -> third_party/net-<target>/{include,lib}
set -euo pipefail
cd "$(dirname "$0")/.."
TARGET=${1:?linux or windows}
TP=$PWD/third_party
SRC=$TP/src
OUT=$TP/net-$TARGET
SMB_URL=https://github.com/sahlberg/libsmb2/archive/refs/tags/v6.0.0.tar.gz
SMB_SHA=c65a7a2b7a6e967a4fd5b6168fca4f1f7d0362a3510767700609c770e3ead1e8
NFS_URL=https://github.com/sahlberg/libnfs/archive/refs/tags/libnfs-6.0.2.tar.gz
NFS_SHA=4e5459cc3e0242447879004e9ad28286d4d27daa42cbdcde423248fad911e747

mkdir -p "$SRC"
fetch() { # url sha file dir
    if [[ ! -d $SRC/$4 ]]; then
        [[ -f $SRC/$3 ]] || curl -fsSL -o "$SRC/$3" "$1"
        echo "$2  $SRC/$3" | sha256sum -c - >/dev/null
        tar xzf "$SRC/$3" -C "$SRC"
    fi
}
fetch $SMB_URL $SMB_SHA libsmb2-6.0.0.tar.gz libsmb2-6.0.0
fetch $NFS_URL $NFS_SHA libnfs-6.0.2.tar.gz libnfs-libnfs-6.0.2

TC=()
if [[ $TARGET == windows ]]; then
    # libsmb2's Windows code expects MSVC's _WINDOWS; MinGW already declares gethostname()
    TC=(-DCMAKE_TOOLCHAIN_FILE=$PWD/cmake/llvm-mingw-toolchain.cmake "-DCMAKE_C_FLAGS=-D_WINDOWS -Wno-int-conversion")
    sed -i 's/^#if defined(_XBOX) || defined(__MINGW32__)$/#if defined(_XBOX)/' "$SRC/libsmb2-6.0.0/lib/compat.h"
    # MinGW has its own asprintf/vasprintf
    grep -q CPLAY_MINGW "$SRC/libsmb2-6.0.0/include/asprintf.h" ||
        sed -i 's/^#define _ASPRINTF_H_$/#define _ASPRINTF_H_\n#ifdef __MINGW32__ \/\/ CPLAY_MINGW\n#include <stdio.h>\n#else/; $ a #endif' "$SRC/libsmb2-6.0.0/include/asprintf.h"
fi
build() { # srcdir name extra...
    local b=$TP/build-net-$TARGET/$2
    rm -rf "$b"
    cmake -S "$1" -B "$b" -G Ninja "${TC[@]}" -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DCMAKE_INSTALL_PREFIX="$OUT" -DCMAKE_POLICY_VERSION_MINIMUM=3.5 "${@:3}" >/dev/null
    cmake --build "$b" >/dev/null
    cmake --install "$b" >/dev/null
}
build "$SRC/libsmb2-6.0.0" libsmb2 -DENABLE_EXAMPLES=OFF -DENABLE_LIBKRB5=OFF -DENABLE_GSSAPI=OFF
build "$SRC/libnfs-libnfs-6.0.2" libnfs -DHAVE_PTHREAD=0 -DENABLE_EXAMPLES=OFF -DENABLE_UTILS=OFF -DENABLE_TESTS=OFF -DENABLE_DOCUMENTATION=OFF
ls "$OUT"/lib*/*.a
echo "net libs ready in $OUT"
