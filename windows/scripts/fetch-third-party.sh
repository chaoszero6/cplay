#!/usr/bin/env bash
# Downloads the Windows binaries CPlay bundles (not kept in git: libmpv is 117 MB).
# Pinned versions, verified by SHA-256.
set -euo pipefail
cd "$(dirname "$0")/.."
TP=third_party

MPV_URL=https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/20261111/mpv-dev-x86_64-20261111-git-bcde6b93cc.7z
MPV_SHA=273c079b11ea5575822f972756f4c9a71149a5c10130353abeb8586738938452
VK_URL=https://repo.msys2.org/mingw/ucrt64/mingw-w64-ucrt-x86_64-vulkan-loader-1.4.317-2-any.pkg.tar.zst
VK_SHA=a52f72d6e80ef4114db16cbfb53b6975af52d7b2fc116f548c27fd5daa6a60f3

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

if [[ ! -f $TP/mpv-win64/libmpv-2.dll ]]; then
    curl -fsSL -o "$tmp/mpv.7z" "$MPV_URL"
    echo "$MPV_SHA  $tmp/mpv.7z" | sha256sum -c -
    mkdir -p $TP/mpv-win64 && 7z x -y -o$TP/mpv-win64 "$tmp/mpv.7z" > /dev/null
fi

if [[ ! -f $TP/vulkan-loader/vulkan-1.dll ]]; then
    curl -fsSL -o "$tmp/vk.pkg.tar.zst" "$VK_URL"
    echo "$VK_SHA  $tmp/vk.pkg.tar.zst" | sha256sum -c -
    tar --zstd -xf "$tmp/vk.pkg.tar.zst" -C "$tmp" ucrt64/bin/vulkan-1.dll ucrt64/share/licenses/vulkan-loader/LICENSE
    mkdir -p $TP/vulkan-loader
    cp "$tmp/ucrt64/bin/vulkan-1.dll" $TP/vulkan-loader/
    cp "$tmp/ucrt64/share/licenses/vulkan-loader/LICENSE" $TP/vulkan-loader/LICENSE-vulkan-loader.txt
fi
echo "third_party ready"
