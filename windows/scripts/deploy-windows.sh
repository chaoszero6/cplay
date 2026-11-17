#!/usr/bin/env bash
# Collects CPlay.exe and everything it needs into build-windows/deploy/CPlay:
# DLLs (found by walking imports), Qt plugins, the QML modules the app imports,
# libmpv, the llvm-mingw C++ runtime and Mesa's software OpenGL fallback.
set -euo pipefail
cd "$(dirname "$0")/.."
QT_WIN=${QT_WIN:-/opt/qt/6.8.2/llvm-mingw_64}
QT_HOST=${QT_HOST:-/opt/qt/6.8.2/gcc_64}
LLVM_MINGW=${LLVM_MINGW:-/opt/llvm-mingw/llvm-mingw-20231128-ucrt-ubuntu-20.04-x86_64}
OBJDUMP="$LLVM_MINGW/bin/llvm-objdump"
OUT=build-windows/deploy/CPlay
rm -rf "$OUT"
mkdir -p "$OUT/plugins" "$OUT/qml"

cp build-windows/bin/CPlay.exe "$OUT/"
cp third_party/mpv-win64/libmpv-2.dll "$OUT/"
cp "$QT_WIN/bin/opengl32sw.dll" "$OUT/"     # used only with --software-opengl / no GPU driver

# Qt plugins
for p in platforms/qwindows.dll styles imageformats/qico.dll imageformats/qjpeg.dll imageformats/qsvg.dll \
         iconengines/qsvgicon.dll tls/qschannelbackend.dll networkinformation/qnetworklistmanager.dll; do
    src="$QT_WIN/plugins/$p"
    [[ -e "$src" ]] || continue
    mkdir -p "$OUT/plugins/$(dirname "$p")"
    cp -r "$src" "$OUT/plugins/$(dirname "$p")/"
done

# QML modules actually imported by our QML files
"$QT_HOST/libexec/qmlimportscanner" -rootPath qml -importPath "$QT_WIN/qml" > build-windows/qmlimports.json
python3 -I - "$QT_WIN/qml" "$OUT/qml" build-windows/qmlimports.json <<'PY'
import json, os, shutil, sys
src_root, dst_root, listing = sys.argv[1], sys.argv[2], sys.argv[3]
seen = set()
for m in json.load(open(listing)):
    path = m.get("path")
    if not path or not path.startswith(src_root) or path in seen:
        continue
    seen.add(path)
    rel = os.path.relpath(path, src_root)
    dst = os.path.join(dst_root, rel)
    os.makedirs(dst, exist_ok=True)
    for name in os.listdir(path):
        s = os.path.join(path, name)
        if os.path.isfile(s) and not name.endswith((".debug", ".pdb")) and not name.endswith("d.dll"):
            shutil.copy2(s, os.path.join(dst, name))
print(f"QML modules: {len(seen)}")
PY
# Only the Basic Controls style is used: drop the other styles (and the DLLs they pull in)
for style in Fusion Imagine Material Universal FluentWinUI3 Windows macOS iOS; do
    rm -rf "$OUT/qml/QtQuick/Controls/$style"
done

# DLL dependencies, recursively, from Qt's bin, llvm-mingw's runtime and libmpv's folder
search=("$QT_WIN/bin" "$LLVM_MINGW/x86_64-w64-mingw32/bin" third_party/mpv-win64 third_party/vulkan-loader)
queue=("$OUT/CPlay.exe" "$OUT/libmpv-2.dll")
while IFS= read -r -d '' f; do queue+=("$f"); done < <(find "$OUT/plugins" "$OUT/qml" -name '*.dll' -print0)
declare -A done_
missing=()
while ((${#queue[@]})); do
    f=${queue[0]}; queue=("${queue[@]:1}")
    for dll in $("$OBJDUMP" -p "$f" | awk '/DLL Name:/ {print $3}'); do
        key=${dll,,}
        [[ -n ${done_[$key]:-} ]] && continue
        done_[$key]=1
        [[ -e "$OUT/$dll" ]] && continue
        found=""
        for d in "${search[@]}"; do
            if [[ -e "$d/$dll" ]]; then cp "$d/$dll" "$OUT/"; found=1; queue+=("$OUT/$dll"); break; fi
        done
        [[ -z $found ]] && missing+=("$dll")
    done
done
printf '[Paths]\nPrefix = .\nPlugins = plugins\nQmlImports = qml\n' > "$OUT/qt.conf"
mkdir -p "$OUT/licenses"
cp assets/fonts/MaterialIcons-LICENSE.txt "$OUT/licenses/MaterialIcons-Apache-2.0.txt"
cp third_party/vulkan-loader/LICENSE-vulkan-loader.txt "$OUT/licenses/vulkan-loader.txt"
cp third_party/THIRD-PARTY.txt "$OUT/licenses/THIRD-PARTY.txt"

# Everything not found must be a Windows system DLL
echo "System DLLs expected from Windows: $(printf '%s\n' "${missing[@]}" | sort -u | tr '\n' ' ')"
du -sh "$OUT"
