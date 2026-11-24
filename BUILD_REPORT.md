# TermiCast Build Verification Report

Updated: 2026-11-24

| Check | Result | Evidence |
|---|---|---|
| Frontend JavaScript syntax | Passed | `node --check` on inline scripts from `index.html` and `tv.html`. |
| Pure Java backend compile | Passed | `javac --release 8` compiled `Server`, `DlnaController` and `DevMain`. |
| Embedded HTTP scan | Passed (JVM host) | `/api/scan` returned sample video and subtitle entries. |
| HTTP media range | Passed (JVM host) | `Range: bytes=2-5` returned HTTP 206, `Content-Range: bytes 2-5/10`, body `3456`. |
| SRT subtitle serving | Passed (JVM host) | `/sub/{id}` returned HTTP 200 `text/vtt`; SRT comma timestamps converted to dots. |
| Android Gradle build | Failed before compilation | `gradle assembleDebug --no-daemon`: SDK location not found; no `sdk.dir`/Android SDK available. |
| APK generated / installed | No / Not verified | Build cannot reach Android compilation; no APK artifact. |
| On-device service/permissions, radio scan, SAF | Not verified | Requires Android SDK/device. |
| TV streaming / DLNA discovery / playback | Not verified on hardware | JVM range test is not a TV test; no renderer available. |

## Implemented in source

- Dark blue monospace/code backdrop UI, four terminal-style tabs, timestamped event log, cinematic player stage with working A-B/night/mirror/bookmark/favorite/timer controls, unified app-level device discovery radar, and per-cast-method information/action cards.
- Wi-Fi scan results, classic Bluetooth discovery/pair requests and paired-device settings route via Android bridge (runtime behavior unverified).
- Manual IPv4 confirmation matches an SSDP-discovered renderer before control; DLNA and browser fallback are separate flows.
- SAF folder grants and scanner, local media byte ranges, SRT/VTT auto-match for scanned media, HTTP SRT-to-WebVTT conversion.
- Existing foreground server and local playback/casting controls preserved; no native Media3/MediaSession or full DSP engine added.

## Known limitations

- Android Java source changes are uncompiled here due missing SDK. APK and install status are therefore unsuccessful/unverified.
- Actual Bluetooth pairing/connectivity, Android permissions, background execution, SAF providers and physical DLNA/browser TV compatibility need device validation. The unified radar is an app-level orchestration layer over Android Wi-Fi/Bluetooth APIs, SSDP/UPnP and the app HTTP client list; it is not a new wire protocol.
- Wi-Fi radar lists visible access points; Android does not expose a complete hotspot client list to this app. Bluetooth is not a TV casting protocol.
- Browser playback depends on receiver codec support; no transcoding. Split mode requests muted TV playback plus local phone playback but synchronization/latency is not hardware-verified.
- `.srt`/`.vtt` matching is available for indexed SAF files, not arbitrary subtitle locations; ASS/SSA is not supported by this endpoint.
- Advanced DSP, audio crossfade, video frame blending, MediaSession/PiP and advertised third-party protocols remain unimplemented.
