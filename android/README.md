# CPlay (Android)

Shown to users as **CPlay** since 1.4.2; internal names (package, classes) stay ChaosPlay.

Android video player in Kotlin + Jetpack Compose + AndroidX Media3
(ExoPlayer). Original code and branding.

Version **1.4.3** (versionCode 8).

## Build

```bash
export ANDROID_HOME=/path/to/android-sdk
cd android
./gradlew :app:testDebugUnitTest          # JVM unit tests (no device)
./gradlew :app:assembleRelease            # release APK (signed if keystore.properties exists)
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest   # device tests
```

APK: `app/build/outputs/apk/release/app-release.apk`

## Toolchain

| Component | Version |
|---|---|
| JDK | 21 |
| Gradle | 8.4 (wrapper committed) |
| AGP | 8.2.2 |
| Kotlin | 1.9.22 / Compose compiler 1.5.10 |
| compileSdk / targetSdk / minSdk | 34 / 34 / 24 |
| Media3 | 1.3.1 (exoplayer, hls, dash, rtsp, session, ui, datasource-okhttp) |
| OkHttp | 4.12.0 |

Signing key: `chaosplay-release.jks` (alias `chaosplay`). Passwords are in
`app/build.gradle.kts`; **move them to a keystore properties file before any
public release.**

## Fixed in 1.2.0: black screen on the second play

Symptom: play a video, go back, play the same or another video: black screen.

Cause: `PlayerScreen` obtained `PlayerViewModel` with a plain `viewModel()`,
which scopes it to the Activity. The second playback got the *same* view
model back: its player had already been `stop()`ped on exit, `initialised`
was still `true` so `initialise()` returned immediately, and the progress
loop had been cancelled. Nothing was ever prepared again.

Fix:
- `PlayerSessionHost` gives every playback its own `ViewModelStore`, cleared
  when the player leaves composition, so each play builds a new view model,
  ExoPlayer and MediaSession, and `onCleared()` really releases them.
- Every playback request carries a nonce (`PlaybackRequest`), so re-opening
  the same video while the previous player is still fading out never re-uses
  the released player content.
- The ExoPlayer is exposed as a `StateFlow`, so the video surface re-binds
  after a decoder switch instead of relying on an incidental recomposition.

Regression tests: `PlaybackReplayTest` (on-device) plays the same video three
times, four different videos in a row, and re-opens during the exit fade,
asserting each time that a new session renders its first frame and advances.

## Architecture

Pure logic is separated from Android so every rule is unit tested on the JVM.
Network sources follow the design notes: the player only sees a location; Jellyfin
items are `jellyfin://server/item` keys resolved to a stream URL at play time,
so tokens never reach history, resume data or logs.

```
app/src/main/java/com/chaoswares/chaosplay/
  player/      PlayQueue (queue/shuffle/loop), Bookmarks, PlayerPrefs + AudioRules
               (boost, EQ), AspectRules, PlaybackErrors, GestureRules, LockRules,
               DecoderRules, PlaybackRules, TimeFormat, SleepTimer        (pure)
  subtitles/   SubtitleParser (SRT, VTT, ASS/SSA, MicroDVD, MPL2, TMPlayer),
               SubtitleEncodingDetector, SubtitleTimeline (delay/speed),
               SubtitleStyle, SubtitleChoices, SubtitleSelector            (pure)
  network/     StreamRules, WebDavRules, JellyfinRules, ServerProfile      (pure)
               NetHttp (one OkHttp client, per-host credentials and trust),
               SecretStore (Android Keystore AES-GCM), NetworkRepository
  data/        Playlists + M3U, PinRules, FileNameRules (pure); stores, scanner
  playback/    PlaybackService (MediaSessionService), PlaybackDiagnostics
  ui/          library, player, network, playlists, settings, theme
```

## What works

| Area | Features |
|---|---|
| **Network** | Play any link: HTTP/HTTPS file, HLS `.m3u8`, DASH `.mpd`, RTSP; saved links. **Nextcloud** (address + user + password/app password, builds the `remote.php/dav/files/<user>/` root itself) and **WebDAV**: folder browsing with breadcrumbs, show-all-files toggle, seekable streaming, sidecar subtitles from the same folder. **Jellyfin**: address check, sign-in, Quick Connect, LAN discovery (UDP 7359), home (continue watching, next up, libraries, recently added), library/series browsing, search, posters, detail sheet with Resume / Start over, direct play with automatic server-transcode (HLS) fallback, server subtitles, start/progress/stop reporting so resume syncs with other Jellyfin apps. Self-signed certificate opt-in per server; passwords and tokens encrypted with the Android Keystore; plain-HTTP-to-internet warning; friendly errors (wrong password, not found, unreachable). |
| **Player** | Bug-free replay; queue from the list you tapped (next/previous, auto-play next, shuffle, loop one/all, queue sheet with reorder/remove); resume (local or server); elapsed/remaining toggle; seek-bar thumbnail preview; A-B repeat with markers; bookmarks with ticks; speed 0.25–4x; aspect Fit/Fill/Stretch/16:9/4:3/100% (Fill and Stretch were swapped before); zoom, pan, mirror, upside-down, rotate; HW/HW+/SW decoder with fallback; screen orientation button (auto/landscape/portrait, landscape for wide video); immersive full screen; screenshot to Pictures/CPlay; share; sleep timer; playback info (resolution, bitrate, buffer, codec). |
| **Gestures** | Brightness (real window brightness), volume (system volume, then boost to 200% via LoudnessEnhancer), seek drag, double-tap skip by the configured step, pinch zoom, two-finger swipe for speed, hold for 2x, optional swipe-down PiP, edge zones ignored; each switchable. |
| **Subtitles** | Embedded tracks by language name, switch live; sidecar files next to local videos (needs All files access on Android 11+), WebDAV sidecars, Jellyfin server subtitles, "Add subtitle file…" picker; second simultaneous subtitle; delay ±; frame-rate speed fix; encoding auto-detect + manual; size, colour, background, outline, bold, position (also drag the text); swipe text to jump lines; remembered per video; preferred language + fallback order. |
| **Audio** | Track selection, preferred audio language, volume boost, equalizer presets, **audio delay** (±10 s in 50 ms steps, live), **volume levelling** (compressor + limiter, Android 9+), **AC3 / E-AC3 / DTS / TrueHD** via the bundled FFmpeg decoder (Jellyfin's `media3-ffmpeg-decoder`, LGPL; platform decoders are tried first). |
| **File shares** | **SMB 2/3** (guest or user, `DOMAIN\\user`, share listing over SRVSVC, or a fixed share), **FTP** (user or anonymous), **SFTP** (password; host key trusted on first use, changed keys refused), **NFSv3** (AUTH_UNIX uid:gid; the export must allow the phone's IP and the `insecure` option). Folder browsing, seekable streaming, sidecar subtitles. Locations are `chaos-smb://<server-id>/…` so passwords never appear in URLs or history. |
| **DLNA / UPnP** | SSDP discovery on Search, or add by description URL; ContentDirectory browsing with paging; plays video/audio items and server subtitles. |
| **Background & PiP** | MediaSession (lock screen, Bluetooth, notification); background play (overflow, long-press play, or Settings); pause on Home otherwise; PiP button and auto-PiP on Home; PiP media controls. |
| **Kids lock** | Four corners clockwise; taps count instantly while locked (quick corner taps used to merge into a double tap). |
| **Library** | Tap plays the visible list as a queue; long-press: favourite, Play next, Add to playlist, Info, Rename, Share, Delete (system consent on Android 11+), Hide; NEW and WATCHED badges; pull to refresh; no full-screen spinner on background refresh. |
| **Playlists & history** | Create, rename, delete, add, reorder, remove, play all, shuffle, M3U/M3U8 import (matched to the library by path then file name; URLs kept) and export; history grouped by day incl. network items; clear with confirmation. |
| **Settings** | Theme; decoder; default speed; seek step; controls auto-hide; resume; auto-play next; background play; auto PiP; landscape for wide video; keep screen on; subtitle language, fallback, encoding, size, colour, background; All files access; audio language; every gesture; sort order; hidden videos behind a PIN (salted hash, lock-out after 5 tries); clear history; clear saved servers and passwords. All persisted. |
| **Open with** | `ACTION_VIEW` from file managers/browsers plays directly. |

## Not done yet (honest list)

- **Plex** source, **Google Cast**, OpenSubtitles online search, libass styled ASS rendering (ASS text shows with italic/bold/underline only), SAMI/PJS parsing, FTPS.
- **NFS** is unit-tested only here: the host's real exports are limited to other IPs and lack `insecure`, and no test NFS server could be run without changing system config.
- **Translations**: the language picker saves the choice, but all UI text is English.
- Android TV, multi-select, two-pane tablet library, offline downloads.
- Not yet tested against a **real** Nextcloud or Jellyfin server: verified against local servers that implement the same API (see Tests). Waiting on test URLs.

## Tests

| Suite | Where | Count |
|---|---|---|
| JVM unit tests (`app/src/test`) | `./gradlew :app:testDebugUnitTest` | 383 |
| On-device tests (`app/src/androidTest`) | emulator, API 36 | 31 |

On-device suites: `PlaybackReplayTest` (black-screen regression),
`PlayerFeaturesTest` (queue, embedded Tamil/English subtitles, sidecar
subtitle, speed, bookmarks, kids lock, PiP), `NetworkPlaybackTest` (HTTP, HLS,
404 message, Nextcloud sign-in/browse/play/sidecar, Jellyfin sign-in/resume/
server subtitle/progress reports), `LibraryAndAppTest` (playlists, Play next,
hide + PIN, rename + delete via system dialogs, settings persistence, Home
pauses vs background play), `SharesAndAudioTest` (SMB, FTP, SFTP, DLNA
against throwaway servers; AC3/E-AC3/DTS/TrueHD decoded by FFmpeg; audio
delay; volume levelling). They assert on real player state
(`PlaybackDiagnostics`: first frame rendered, position advancing, session id),
not on pixels.

Running them needs test media in `/sdcard/Movies/ChaosTest` (Alpha Clip.mp4 +
Alpha Clip.srt, Bravo Bars.mp4, Charlie Fractal.mp4, Delta Tracks.mkv with
eng/tam audio and subtitles, Echo Disposable.mp4) and, for the network suite,
local servers on the host: static HTTP/HLS on :8765, a Nextcloud-layout
WebDAV on :8766 (test/secret) and a mock Jellyfin on :18096 (test/secret).

Emulator notes for this host: run `adb shell settings put global
hide_error_dialogs 1` (System UI ANR dialogs otherwise steal input), reduce
the display (`wm size 720x1600`), and stop Gradle daemons first; the software
GPU takes seconds per frame at full resolution.

Bugs found while testing: the black screen (above); Fill/Stretch aspect modes
swapped; brightness gesture never changed the screen; volume boost above 100%
had no effect; pinch zoom only changed a label; elapsed/remaining toggle did
nothing; quick corner taps lost while locked; the open playlist was forgotten
after playing a video.

## Licensing

CPlay is licensed under the GNU GPL v3 (see `../LICENSE`), because it bundles
Jellyfin's Media3 FFmpeg decoder (GPL-3.0) for AC3/E-AC3/DTS/TrueHD audio.
Other libraries: Media3 and OkHttp (Apache-2.0), smbj (Apache-2.0), dcerpc
(BSD-3-Clause), Apache Commons Net (Apache-2.0), JSch (BSD) and nfs-client
(Apache-2.0).

Release signing reads `android/keystore.properties` (`storeFile`,
`storePassword`, `keyAlias`, `keyPassword`); that file and any `.jks` are
never committed.
