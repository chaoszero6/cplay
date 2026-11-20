# CPlay

**Every video. Every format. Zero ads.**

CPlay is a free, open-source video player for **Android** and **Windows**.
No ads, no tracking, no account. Ever.

| | Android | Windows |
|---|---|---|
| Version | 1.4.3 | 1.0.0 |
| Engine | AndroidX Media3 + FFmpeg audio | libmpv (FFmpeg) |
| Source | [`android/`](android/) | [`windows/`](windows/) |

Downloads are on the [Releases](../../releases) page.

## Android

**Plays practically everything**
- MKV, MP4, AVI, WebM, HEVC and more, plus HLS and DASH streams
- AC3, E-AC3 and DTS audio built in: no codec packs
- Subtitles: SRT, ASS/SSA, VTT, automatic encoding detection, adjustable style and timing

**Made for watching**
- Swipe gestures for brightness, volume and seeking; double-tap to skip
- Resume where you left off, bookmarks, A-B repeat, speed control, sleep timer
- Audio delay, volume levelling and boost
- Picture-in-picture and background play
- Kids lock with touch effects, so little ones can't stop the video

**Your home network, built in**
- SMB/Windows shares, FTP, SFTP, NFS and DLNA servers
- Jellyfin (with synced watch progress) and Nextcloud/WebDAV
- Play on TV: send videos to DLNA smart TVs and Chromecast

**Respects you**
- No ads, no analytics, no sign-up
- Server passwords are stored encrypted on your device and only go to your own servers

## Windows

A classic desktop player with a library and your home network built in, and no ads:

**Plays practically everything**
- Every common format without codec packs (libmpv/FFmpeg), with hardware decoding
- Subtitles with delay, size, position and text-encoding controls; audio delay and volume levelling

**A player that stays out of the way**
- Compact skinned window: drag it anywhere, it snaps to screen edges and corners, one click pins it on top, mini player
- Window size on play: keep your size, always use a fixed size, or fit each video
- Seek-bar preview thumbnails, resume, A-B repeat, bookmarks, sleep timer, repeat one/all
- Playlist panel: the rest of the folder is queued automatically; add, reorder, shuffle, save and open .m3u playlists
- Control box: brightness, contrast, saturation, hue, gamma, zoom, rotate, mirror, aspect ratio, deinterlace
- Lock mode, taskbar progress and buttons, one window for every video, full keyboard and mouse control

**Library**
- Finds the videos in your folders, with thumbnails, lengths and watch progress
- All videos, Folders, Recent and Favorites; grid or list; sort and search
- Continue watching; rename, show in Explorer, move to the Recycle Bin

**Your home network, built in**
- Jellyfin (Continue watching, Next up, search, synced watch progress), Nextcloud and WebDAV
- Windows shares (SMB), FTP, NFS and DLNA media servers, plus saved stream links
- Play on TV: send videos to DLNA smart TVs and Chromecast, with a remote control on screen
- Passwords are encrypted for your Windows account and only go to your own servers

## Building

### Android
```bash
cd android
./gradlew :app:testDebugUnitTest :app:assembleRelease
```
Release signing reads `android/keystore.properties` (`storeFile`, `storePassword`,
`keyAlias`, `keyPassword`); without it the release APK is unsigned. See
[`android/README.md`](android/README.md).

### Windows (cross-built from Linux)
Needs Qt 6.8.2 (`gcc_64` and `win64_llvm_mingw`, e.g. via `aqtinstall`), llvm-mingw 17.0.6 (UCRT),
CMake, Ninja, libmpv headers, NSIS and 7-Zip. Wine is optional, for the smoke test.
```bash
cd windows
scripts/build-linux.sh --test    # Linux build of the same code + all tests
scripts/build-windows.sh         # fetches libmpv, builds libsmb2/libnfs, cross-compiles CPlay.exe, collects its DLLs
scripts/smoke-wine.sh            # CPlay.exe --self-test under Wine
scripts/package.sh               # dist/CPlay-Windows-<version>-Setup.exe and -portable.zip
```
Paths to the toolchains are set at the top of the scripts and in `cmake/llvm-mingw-toolchain.cmake`.

### Tests
- Android: 393 JVM unit tests, plus device tests in `android/app/src/androidTest`
- Windows: Qt Test suites (`ctest`), including headless playback of every clip in `tools/test-media`,
  and end-to-end network and Play on TV tests against the mock servers
- `tools/test-servers/`: local mock servers (HTTP/HLS, WebDAV, Jellyfin, SMB, FTP, SFTP, DLNA, a DLNA TV, a Chromecast)

## License

CPlay is free software under the **GNU General Public License v3.0** ([LICENSE](LICENSE)).
It uses Media3, OkHttp, Qt (LGPL-3.0), libmpv/FFmpeg, the Jellyfin FFmpeg decoder, smbj, JSch,
Apache Commons Net, nfs-client, libsmb2, libnfs, the Vulkan loader and Google's Material Icons; their licences
are listed in [`android/README.md`](android/README.md) and
[`windows/third_party/THIRD-PARTY.txt`](windows/third_party/THIRD-PARTY.txt).
