# TermiCast — Hinglish Build Guide

## Architecture

TermiCast ek single Android app hai: Java foreground service ke andar local HTTP server chalta hai; Android WebView mein UI, aur packaged browser receiver TV ke liye hai. Backend pure Java hai—Node.js/npm/Termux install nahi chahiye. Offline local playback, local server aur LAN discovery supported hain.

## Source map

- App UI: `app/src/main/assets/web/index.html` (SYS_INIT, EXPLORER, NEURAL_Q, CMD_CTR; blue monospace theme)
- TV receiver: `app/src/main/assets/web/tv.html`
- Android bridge/permissions/device radar: `app/src/main/java/com/termicast/MainActivity.java`
- Foreground server lifecycle: `app/src/main/java/com/termicast/TermiService.java`
- MediaStore + SAF folder scanner/URI streaming: `app/src/main/java/com/termicast/AndroidHost.java`
- HTTP API, byte ranges, subtitle WebVTT endpoint: `app/src/main/java/com/termicast/server/Server.java`
- SSDP, UPnP AVTransport + controls: `app/src/main/java/com/termicast/server/DlnaController.java`
- PC smoke runner: `dev/DevMain.java`

## Build / install

Java 17, Android SDK, Gradle 8.7 and Android Gradle Plugin 8.5.2 chahiye. Repo mein Gradle wrapper nahi hai. Project root se:

```sh
gradle assembleDebug --no-daemon
```

APK output `app/build/outputs/apk/debug/app-debug.apk` hona chahiye. Is workspace mein Android SDK configured nahi tha, isliye current APK generate/install nahi hua. Release signing configure nahi hai. GitHub Actions workflow preserve hai.

## First run / server

App launch par foreground server service start hoti hai (port 8765, busy ho toh next port); permission prompts allow karein. SYS_INIT dashboard IP, port, device/CPU aur server status dikhata hai. Home ke server control se server start/stop hota hai. Wi-Fi/Bluetooth radar alag Android runtime permissions maangta hai; Android policy ke kaaran Wi-Fi list access points hai, full client list nahi. Bluetooth pairing ke baad audio device selection Android Bluetooth settings mein hoti hai—yeh DLNA cast nahi.

## TV / cast

Phone aur TV same reachable LAN par hon. Cast Methods mein har supported option ka info card hai:

- **Auto-DLNA:** SSDP se AVTransport-capable renderer search, phir media URI/play command.
- **Manual IP:** IPv4 enter karein; ALLOW par SSDP result IP match ho tabhi use hoga.
- **Browser fallback:** TV ke browser mein `http://PHONE_IP:PORT/tv` kholein. Local HTTP range stream TV browser codec compatibility par depend karti hai.

Player mein Browser TV, DLNA, Mobile, Dual aur Split (TV muted, phone audio) modes hain. Paired TV details radar mein aate hain. Bluetooth/Wi-Fi Direct/Miracast/Chromecast is build mein implemented nahi. Phone IP app dashboard par check karein.

## Player aur Unified Device Radar

Player screen screenshot ke cinematic layout ko follow karta hai: large video stage, overlay seek/transport, cast/subtitle/tools buttons, quick actions, mode pills aur volume. A-B repeat local/browser receiver playback par work karta hai; sleep timer pause command bhejta hai; night tint, mirror, bookmarks, favorites, delete aur timestamp skips in-app actions hain. Favorites Explorer search mein `favorites` term se filter hote hain. Unsupported DSP/audio-track switching ko player fake controls ke roop mein nahi dikhata.

Device radar ek **app-level discovery protocol/orchestrator** hai (naya network standard nahi): WLAN access-point scan → Bluetooth discovery/bonding → SSDP/UPnP renderer discovery → active TermiCast LAN receivers. Sabko normalized device rows aur All/Wi-Fi/BT/DLNA/LAN filters mein dikhata hai. Har protocol ka action apne Android/network API se execute hota hai; Wi‑Fi access point ko TV renderer nahi maana jata.

## Library / player

Explorer mein Mount folder dabakar Android SAF se directory grant karein, phir scan hoga. MediaStore videos/audio bhi indexed hote hain. Matching `.srt`/`.vtt` files selected SAF folder se match hoti hain; SRT local endpoint par WebVTT mein convert hoti hai. Browser/WebView codec support ke mutabik local playback; HTTP Range streaming/seek backend mein hai. CMD_CTR mein transport, seek, estimated 25 FPS frame step, timestamp skip, theater, intro skip aur LAN party controls hain. Frame step approximation hai; exact FPS metadata is UI se report nahi hota.

## Known limitations / troubleshooting

- Current environment mein `gradle assembleDebug` SDK path missing ke kaaran Android compile se pehle fail hua; Android-side changes unverified.
- Physical TV/DLNA interoperability, Bluetooth runtime behavior, SAF device testing, install/launch, background playback on device abhi validate nahi hue.
- Browser TV codecs device ke native decoder par depend karte hain; server transcode nahi karta.
- App mein Media3/MediaSession native playback engine, full audio DSP/crossfade, true video frame blend, PiP, native split-audio sync aur advanced filters nahi hain. UI feature claims in capabilities ko imply nahi karte.
- Android permission decline par Settings mein app permissions enable karke rescan karein. TV discovery fail par same LAN, multicast aur router client isolation check karein.

## Future updates

Android SDK se build karke pehle install/SAF/radio/server lifecycle test karein; phir real DLNA TV/browser par range seek aur controls validate karein. Native background player/DSP ke liye Media3 service integrate aur separately test karein. `BUILD_REPORT.md` mein observed results hi record karein.
