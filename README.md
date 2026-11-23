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

### v0.5.7 — PiP fixed at the root, lag fixes, whole-UI animations
- **Picture-in-Picture fixed at the root cause** — Android fires `onPause` before `onPictureInPictureModeChanged`, and the player was pausing and releasing itself during PiP entry. The lifecycle now checks the real PiP state; playback continues inside PiP and the surface re-attaches on PiP mode change (HW and SW).
- **Video lag fixed** — the 100 ms UI tick now only moves the seekbar and time labels; subtitle lookup is binary search and updates the view only on cue change; the FPS probe moved off the UI thread; lifecycle saves are async; the SW engine's render/audio threads run at urgent display/audio priority; the maximum display refresh rate is requested.
- **UI lag fixed** — the library display model is built on a background executor and applied under a generation guard; the list adapter reads precomputed row metadata.
- **Smooth animations across the whole UI** — screen transitions (fade + slide, 240–260 ms), player chrome glides at 260 ms, menu/sheet/drawer slides, dialog animations, library list entrance cascade, row press feedback.
- All 619 checks pass, 0 failures. Same 7 permissions, no internet permission; installs over v0.5.6 keeping data.

### v0.5.6 — playback smoothness (first PiP attempt)
- Player requests the display's highest refresh rate while open, so 24 fps content gets an even cadence instead of 60 Hz pulldown judder.
- AV-sync corrections require 2 consecutive ticks before acting, so poll noise no longer causes stutters.
- The periodic position save no longer writes the whole state file on the UI thread.
- PiP: SW surface re-attaches on a real size change, aspect ratio clamped into the framework range, source-rect hint on entry (superseded by the v0.5.7 lifecycle fix).

## Build
No Gradle — a manual pipeline (`build.sh`): aapt2 → kotlinc → d8 → zipalign → apksigner.
Requires JDK 21 and the Android SDK (build-tools 35.0.0). The native engine is built with NDK r27c.

## Privacy
The app declares no INTERNET permission and contains no analytics or location access. All data stays on the device.

Current version: 0.5.7 (versionCode 13), minSdk 24, targetSdk 34.
