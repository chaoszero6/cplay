#!/usr/bin/env bash
# Runs the deployed Windows build under Wine: "CPlay.exe --self-test <clip>"
# loads Qt and libmpv, decodes the clip, reaches the end and replays it.
# Catches missing DLLs and startup crashes before a build goes to a real PC.
set -uo pipefail
cd "$(dirname "$0")/.."
# Outside the source tree: a Wine prefix links z: to / and tools that scan folders would follow it
export WINEPREFIX=${WINEPREFIX:-$HOME/.cache/chaosplay-wineprefix}
export WINEDEBUG=-all
export DISPLAY=${DISPLAY:-:91}
pgrep -f "Xvfb $DISPLAY" >/dev/null || (Xvfb "$DISPLAY" -screen 0 1600x900x24 >/dev/null 2>&1 &)
[[ -d "$WINEPREFIX" ]] || wineboot --init >/dev/null 2>&1
EXE=build-windows/deploy/CPlay/CPlay.exe
status=0
for clip in "Alpha Clip.mp4" "Hotel DTS.mkv" "Foxtrot AC3.mkv"; do
    path="$(winepath -w "../tools/test-media/$clip")"
    echo "== $clip"
    out=$(timeout 120 wine "$EXE" --self-test "$path" 2>&1 | tr -d '\r')
    echo "$out" | grep -v -E '^(fixme|err):' | tail -6
    echo "$out" | grep -q "SELF-TEST OK" || status=1
done
exit $status
