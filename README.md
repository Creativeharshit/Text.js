# TermiCast — build aur architecture guide

Yeh native Android wrapper, browser receiver aur basic DLNA/UPnP renderer client wala offline media sharing project hai. APK ke andar Java HTTP server aur WebView UI hain. DLNA implementation source mein hai; real TV interoperability abhi verify nahi hui. TV browser ka `/tv` receiver bhi available hai.

## Architecture

- `app/src/main/java/com/termicast/MainActivity.java`: WebView shell aur JavaScript permission/settings bridge.
- `TermiService.java`: foreground service; server ko port 8765 se start karta hai.
- `AndroidHost.java`: MediaStore content URIs se media index, range reads aur delete request; packaged web assets bhi deta hai.
- `server/Server.java`: Java HTTP/1.1, JSON API, SSE, PIN pairing, file streaming aur byte ranges. Koi Node.js/npm runtime nahi.
- `server/DlnaController.java`: SSDP discovery, UPnP device parsing, AVTransport load/control/state aur renderer volume.
- `app/src/main/assets/web/index.html`: phone control UI/library/queue/player.
- `app/src/main/assets/web/tv.html`: browser-based TV receiver.

Server TV aur phone ke ek hi Wi-Fi/LAN par kaam karta hai; internet zaroori nahi. Phone hotspot par clients ko ek dusre tak pahunch milni chahiye. Wi-Fi Direct, Miracast aur Chromecast integrated nahi hain.

## Build karna

Android Studio ya Java 17 + Android SDK ke saath Gradle 8.7 aur Android Gradle Plugin 8.5.2 use karein:

```sh
gradle assembleDebug --no-daemon
```

APK yahan milegi: `app/build/outputs/apk/debug/app-debug.apk`. Is project mein Gradle wrapper committed nahi hai. GitHub Actions workflow bhi Gradle 8.7 se debug APK banata hai.

## Install aur istemal

APK ko Android device par install karke TermiCast kholein. Media aur Android 13+ notification permissions allow karein. DLNA ke liye Player → DLNA TV ya Cast hub → DLNA Discovery chunein, renderer scan/select karein, phir media play karein. Browser receiver ke liye TV par `http://PHONE_IP:8765/tv` kholkar Browser TV mode use karein. DLNA compatibility TV/router/codec par depend karti hai aur physical device test pending hai.

Phone ka IP app/server status mein ya Wi-Fi network details mein dekhein. Default port 8765 hai; occupied hone par server agle 9 ports try karta hai. Dusre browser se `http://PHONE_IP:8765/` kholkar PIN pairing se remote control kiya ja sakta hai; PIN app ke terminal command `pin` se milega.

## Common issues

- **Media list khaali:** app media permissions check karein. Scanner indexed MediaStore library tak limited hai; arbitrary folders/USB drives ke liye SAF folder picker abhi nahi hai.
- **TV connect nahi hota:** dono devices ek hi LAN par hon; guest/client isolation band ho; TV browser mein `/tv` kholkar page active rakhein.
- **Playback unsupported:** TV browser/codecs file format support karein. Server file bytes stream karta hai, transcode nahi karta.
- **Port / service error:** app dobara kholein, doosre server band karein, notification/foreground service status dekhein.
- **DLNA device list nahi:** TV mein DLNA/media renderer enable karein, devices same LAN par hon aur router client isolation/multicast block na kar raha ho. Device interoperability abhi test nahi hui.

## Aage development

SAF folder browsing aur persistable grants add karein. DLNA ko real TV models par test karke renderer-specific MIME/seek quirks fix karein. Native playback/fade DSP ke liye Media3/AudioTrack pipeline aur format/license review chahiye. Settings UI mein dikhne wale controls ko engine se wire kiye bina feature complete na samjhein.
