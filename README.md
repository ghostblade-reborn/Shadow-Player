# Shadow Player

A fully offline Android video player written in Kotlin — no internet permission, no trackers, no ads. Your media never leaves your phone.

Formerly developed as GhostBlade Player; renamed to Shadow Player in v0.5.5.

## Features
- Local video and music library with folder browsing
- Hardware (HW/HW+) and software (SW) decoding — own FFmpeg 7.1.1 build (LGPL, network compiled out)
- MX-style player controls: gesture brightness/volume, double-tap seek, playback speed, aspect ratio, PiP
- Subtitle support (SRT, ASS/SSA, and more), resume playback, playlists
- Follows the system light/dark theme

## New Changes

### v0.5.7
- Picture-in-Picture fixed — playback continues inside PiP
- Video lag fixed — smoother playback, lighter UI thread
- UI lag fixed — library lists load in the background
- Smooth animations added across the whole UI
- 619 checks pass · 7 permissions · no internet

### v0.5.6
- Smoother playback — max refresh rate, steadier AV sync
- No more UI hitch from position saving

## Build
No Gradle — a manual pipeline (`build.sh`): aapt2 → kotlinc → d8 → zipalign → apksigner.
Requires JDK 21 and the Android SDK (build-tools 35.0.0). The native engine is built with NDK r27c.

## Privacy
The app declares no INTERNET permission and contains no analytics or location access. All data stays on the device.

Current version: 0.5.7 (versionCode 13), minSdk 24, targetSdk 34.
