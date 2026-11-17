#!/usr/bin/env bash
# Builds the installer and the portable zip from build-windows/deploy/CPlay.
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION=$(sed -n 's/^project(ChaosPlay VERSION \([0-9.]*\).*/\1/p' CMakeLists.txt)
mkdir -p dist
makensis -V2 -DVERSION="$VERSION" -DSRCDIR="$PWD/build-windows/deploy/CPlay" \
    -DOUTFILE="$PWD/dist/CPlay-Windows-$VERSION-Setup.exe" installer/chaosplay.nsi
rm -f "dist/CPlay-Windows-$VERSION-portable.zip"
(cd build-windows/deploy && 7z a -tzip -mx=9 -bd -bso0 "../../dist/CPlay-Windows-$VERSION-portable.zip" CPlay)
ls -la dist/
sha256sum dist/*"$VERSION"*
