# TermiCast Project State

Updated: 2026-11-24

## Current architecture

Existing project is Android Java foreground service + embedded pure-Java HTTP/SSE server + WebView frontend/TV receiver; no Node runtime. Work is in this extracted project. Uploaded original ZIP is untouched.

## Work completed

- Blue/cyan monospace design, code-text background, terminal-tab navigation, animated glass cards, persistent timestamp/sequence event log and home dashboard.
- Android foreground server start/stop guard, content-URI media handling and SAF folder picker/scanner.
- HTTP range response validation and bounded worker pool.
- SSDP UPnP renderer discovery and AVTransport/RenderingControl operations wired into cast/player UI.
- Android Wi-Fi scan and Bluetooth discovery/pair/settings bridge plus device radar (not runtime tested).
- Cast method instruction/details cards; Auto-DLNA, explicit manual-IP confirmation against discovered renderer, browser receiver fallback.
- Queue preflight via HTTP range, estimated frame-step controls, intro/timestamp skip, theater, LAN SSE party controls, split mode request.
- Added `.srt`/`.vtt` SAF indexing, same-basename match, local `<track>` and TV load subtitle, SRT-to-WebVTT endpoint with 2 MiB cap.
- Redesigned CMD_CTR player around screenshot-inspired video stage/seek overlay/transport, quick actions, mode selector and cast/subtitle/tools menu; functional A-B loop (local/TV browser), sleep timer, night tint, mirror, bookmarks, favorite filtering and menu actions.
- Unified device radar groups Wi-Fi AP, Bluetooth, DLNA and LAN endpoints into one normalized application-level discovery protocol with filters and actions; protocol meaning is orchestration, not a new network standard.
- Readme and build report refreshed.

## Verification

- Node syntax checks passed for both WebView pages.
- `javac --release 8` passed for server/DLNA/JVM runner.
- JVM HTTP smoke: scan returned media + subtitle; Range returned 206 exact bytes; subtitle returned converted WebVTT.
- `gradle assembleDebug --no-daemon` failed before Android compile because Android SDK location is missing. APK absent; no install/device/physical-TV test.

## Remaining

- Build with configured Android SDK and resolve any Android compiler issues.
- Validate runtime permissions, Wi-Fi/Bluetooth scan, SAF providers, service lifecycle and background behavior on device.
- Validate actual DLNA renderer and browser TV playback/control.
- Native Media3/MediaSession, full fade/crossfade/DSP, exact frame stepping, true dual audio synchronization, advanced media lab/network protocols are not implemented.
