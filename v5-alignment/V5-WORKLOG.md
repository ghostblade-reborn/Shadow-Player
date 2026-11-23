# GhostBlade Player v0.5 — MX Alignment & Icon Fidelity Pass — WORKLOG

Started: 2026-11-22 ~21:10 Dhaka, on Sir's device verdict on v0.4:
"Better now. But not exactly same as MX Player. Icons are not aligned. Measure everything, measure alignment. Fit everything same as MX Player. All icons redesign same as MX. Those are not same. Keep doing work — still far away from MX Player."

## Sir's orders for this pass
- Measure EVERYTHING: alignment first — icon positions, containers, padding, spacing, baselines on every surface.
- Redesign ALL icons same as MX (redrawn from MX's measured geometry, as before — MX's artwork files themselves are not lifted).
- Fit everything same as MX Player. Nothing else changes: same features, same cuts (no internet, no ads, no MX Share), fully offline.

## Teams
- Shadow Measure Team — RUNNING (3 agents): MX alignment + icon geometry measurement vs our v0.4 code → ALIGNMENT-SPEC.md
- Shadow Icon Team — WAITING: redraw every icon to the measured geometry
- Shadow Rebuild Team — WAITING: apply alignment fixes + new icons → build v0.5
- Shadow Monitoring — WAITING: independent audit vs MX spec
- Jervis Verification & Delivery — WAITING: personal suite re-run + APK verification + delivery

## Progress
- Programme started 21:10. Board set to v0.5 programme first, per Sir's standing rule.
- 21:15 — Sir restructured the work: Jervis does it himself, ONE assist team only. Measure coordinator shut down; its two finished measurement files salvaged (parts/player.md, parts/library-settings.md).
- 21:29 — Jervis completed the hands-on build (see below). **Overall programme: 75%** — build + Jervis verification done; assist-team audit running; delivery after audit.

## What Jervis changed in v0.5 (hands-on)
Player: MxIcon glyphs redrawn to measured MX ink (PLAY/PAUSE at 32dp box, NEXT/PREV 18×18, FORWARD/BACKWARD double-triangles, LOCK, ROTATION phone+lock, SUBTITLE card, BACK, PIP, PLAYLIST, ASPECT outlines, FOLDER filled); play/pause render box 32dp; centre pitch spread to ~99dp in landscape (clamped 0 in portrait); title 14sp; aspect OSD 52sp; brightness/volume OSD rebuilt to decoded BrightnessBar (42dp wide, 20dp icon, 4×92dp bar, 13sp %, 16dp margin); drawer 200×360dp; strip speed text 12sp, red dot 10dp, chevron disc 30dp.
Library: app bar = fixed 48×48dp icon buttons with drawn glyphs (was uneven Unicode text buttons); view-toggle in bar; root title "Folders"; NEW quick-access row (Music/My Playlists/Cleaner/Privacy — File Transfer + Status Saver cut); NEW bottom nav (Local/Music/Me — Transfer cut); folder rows: 76dp slot + grey folder vector at device size ~70×53dp, numbered red badge (20dp) straddling the glyph corner, "N videos" wording, third line removed; drawn type/heart/FAB glyphs; folderMini proportions fixed; Settings/Surfaces titles bold.
Spec correction found while coding: player P1-7 was a misread — "sticky" already IS background play with the headphones glyph, and DEFAULT_RAIL order already matches MX's device strip order. No change made there.
Not done (honest): subtitle tag on thumbs (needs embedded-subtitle detection wiring); floating-bar steppers 52dp (P2-9); unverified-glyph set (P3-11) — rasters not individually measured.

## Jervis verification (2026-11-22 ~21:35)
- Suites: TestMain 208, PlayerLogicTest 162, SubtitleTest 95, SubtitleFormatsTest 103, ClipEditorTest 51 = **619 checks, 0 failures**.
- APK: **GhostBladePlayer-v0.5.apk 6,386,459 bytes, SHA-256 fc342660981db7cfad64c18268803c9bd0dda05b3e29aa3b94d0592ce72b01d2, versionCode 6 / 0.5, cert e38da55a… (installs over v0.4), exactly 7 permissions, NO INTERNET, 11 native libs, cutout shortEdges in binary manifest.**


## Sources of truth
- MX decoded tree: ~/workspace/projects/mxplayer-analysis/decoded/ (+ RESOURCE-TRUTH.md)
- Master scene spec: ~/workspace/projects/mxplayer-analysis/MASTER-SCENE-SPEC.md
- Icon catalogue: ~/workspace/projects/mxplayer-analysis/mx-icons/ICON-CATALOGUE.md
- Our source: ~/workspace/projects/ghostblade-player/
- This pass: ~/workspace/projects/ghostblade-player/v5-alignment/

## AUDIT + FIXES + DELIVERY — 2026-11-22 ~21:47
- Shadow Assist Team audit (1 agent, read-only): PASS-WITH-NOTES. All 15+ MxIcon
  redraws, app bar, quick row, bottom nav, folder rows, ic_folder.xml verified
  with line evidence. Spec P1-7 confirmed a spec misread (Jervis correct).
- P1-1 FIXED: the 200x360 MX player-menu size had landed on the playlist
  drawer; menuPanel is now 200x360 bottom-right (view_player_menu.xml),
  drawerPanel restored to 300dp full-height right.
- P1-2 FIXED: centre pitch was frozen at entry orientation; play-button
  margins now recomputed in onConfigurationChanged via updateTransportGap()
  (player entered from portrait library now gets the ~99dp landscape pitch).
- P2 FIXED: folder badge re-anchored END with -7dp right margin — centre on
  the glyph's true corner (73.0, 9.8); 2-digit counts grow leftward.
- P2 FIXED: floating-bar steppers 42dp -> 52dp (MX PlaybackFloatingButton).
- Final rebuild verified by Jervis personally: suites 619 checks, 0 failures
  (208/162/95/103/51). APK GhostBladePlayer-v0.5.apk 6,386,459 bytes,
  SHA-256 7eaa988dacaa980999c07545016c9893bd39c0fb82ee07a3055eb40001134008,
  versionCode 6 / 0.5, cert e38da55a..., exactly 7 permissions, NO INTERNET,
  11 native libs, cutout shortEdges=1 in binary manifest.
- Still open (honest): subtitle/SSA tag on thumbs (needs detection wiring),
  ~15 unverified MxIcon kinds, selection action-mode icon buttons, settings
  row heights, music screens beyond title weight. Awaiting Sir's device test.

## v0.5.1 — STRIP ICON ALIGNMENT FIX — 2026-11-22 ~21:57
- Sir device-tested v0.5, sent collapsed + expanded strip screenshots:
  "Those icon still not aline measure it and fix it."
- MEASURED (visual grounding on his screenshots): circles are in a straight
  row; the drawn glyphs sat ~6-8dp BELOW circle centre (equalizer +8.1dp,
  rotation +8.2dp, screenshot/headphones ~+5.7dp) while the "1X" text was
  dead centre. Expanded strip labels clipped to slivers.
- ROOT CAUSE (code): stripCircle's glyph ImageView was LayoutParams(40,40)
  PLUS 8dp inset margins inside the 40x40 wrap -> glyph centre (28,28),
  bottom clipped. Chevron same bug (30,30)+8dp margins in a 30dp frame.
  Expanded scroll height was fixed dp(50) vs ~73dp cells.
- FIXES: glyph box 24x24 + 8dp margins (centre 20,20); chevron 14x14 + 8dp
  margins (centre 15,15); expanded scroll height WRAP + row padding.
- Assist audit (1 agent): PASS, no P1/P2; whole-source sweep of all 16 .kt
  files found ZERO other instances of the size+margins defect pattern.
- Jervis re-verified: suites 619 checks, 0 failures. APK
  GhostBladePlayer-v0.5.1.apk 6,386,459 bytes, SHA-256 49b80032...,
  versionCode 7 / 0.5.1, cert e38da55a..., exactly 7 permissions,
  NO INTERNET, cutout shortEdges=1. Awaiting Sir's device test.

## v0.5.2 — Sir's device round (2026-11-23 ~02:31–02:45), JERVIS SOLO (Sir: "Don't bring any team this time do everything by your own")

Evidence: our Folders screenshot (light), MX Folders screenshot (system-dark), annotated screenshot (red ovals), 22.9s screen video.
1. TAP BUG: single tap toggled play/pause (PSet.touchAction default mode 0 paused on centre tap; double-tap play/pause defaulted OFF). Fix: onSingleTapConfirmed now ONLY show/hide interface; centre double tap = play/pause with gDblTapPlayPause default flipped to true; centre double-tap-zoom branch removed (pinch zoom remains); side double-tap seek unchanged. Settings kept honest: "Touch action" ListPref + "Double tap: Zoom" Check nodes and their wiring REMOVED.
2. TRANSPORT ALIGNMENT: grounding on Sir's video frame — lock (75,851), rewind (131,855), prev (384,855), play (491,854), next (600,855), forward (653,856). The seek pair was asymmetric (backward far-left beside lock, forward beside next). Fix: backward button moved into the centre cluster (backward | prev | play | next | forward); transportGapDp constant 466 -> 416 (sides 166 + centre 250); LOCK/UNLOCK ink optically centred (+0.5u, ink spans y1-22).
3. SYSTEM THEME: LIST_THEME default "white" -> new "system" value (now first in LIST_THEMES, label "System default"); Settings.currentThemeColors(ctx) resolves system -> BLACK (dark) / LIGHT (light) from uiMode; all 12 call sites (MainActivity 2, SettingsActivity 2, Surfaces 7, ClipEditorActivity 1) swapped; MainActivity.onResume already rebuilds on theme change. Folder glyphs: drawable-night/ic_folder.xml slate variant (verified inside APK as res/drawable-night-v8/ic_folder.xml) + folderMiniDrawable(dark) slate paints.
4. REMOVALS (annotated ovals covered ONLY these): quick-access row (buildQuickRow, field, addView, updateChrome block) and bottom nav (buildBottomNav, field, addView, updateChrome block) deleted; Music/Playlists/Private Folder/Cleaner/Settings all remain in the overflow menu.
Tests: 3 TestMain pins updated to the new intended defaults (list theme "system", themes count 26, persistence default). Final: 619 checks, 0 failures (208/162/95/103/51).
APK (Jervis-verified): GhostBladePlayer-v0.5.2.apk 6,386,540 bytes, SHA-256 168699ab7d07c54836446406e47e821d2a9f5f9e89ae7ba53e08e948b4f17e5d, versionCode 8 / 0.5.2, cert e38da55a (installs over v0.5.1), exactly 7 permissions, NO INTERNET, 11 native libs, cutout shortEdges=1. No GitHub push ordered.

## v0.5.3 — Sir's resume-button + badge + bar-icon round (2026-11-23 ~02:52–03:00), JERVIS SOLO

Evidence: Folders screenshot (badges clipped, FAB circled), dark app-bar crop (bar icons tiny), grounding on badges: centre (207,133) vs glyph corner (216,144).
1. RESUME BUTTON (Sir: "the most important button for me"): the FAB existed and showed, but playLast went through the player's resumeMode (default "ask") — a prompt instead of the exact second. Fix: launchPlayer gains resumeExact; playLast (FAB + app-bar play-last) passes it; PlayerActivity reads resume_exact into exactResumePath; BOTH resume handlers (handleResume HW/HW+, swHandleResume SW) seek straight to Store.resumePos for that path, bypassing Ask/Start-over, then clear the flag. Normal opens keep the Ask behaviour.
2. BADGES: the numbered red badge straddles the folder corner with a -7dp right margin — 7dp of it lives outside iconWrap, and the row/iconWrap clipped children, cutting the badge to a half-dome with squeezed numbers. Fix: clipChildren=false + clipToPadding=false on the folder row and iconWrap; badge now renders whole, centre on the glyph corner (73.0, 9.8), 2-digit counts grow leftward.
3. BAR ICONS TOO SMALL: bar glyphs (PLAY_LAST/SEARCH/GRID/LIST) had 14–18u ink in the 24u drawable box. Fix: those four GlyphDrawable kinds are drawn scaled x1.35 about the centre (ink ~22–24dp); PLAY_LAST also redrawn bolder (rect 19x15u, stroke 2.0u, triangle 7x7.6u). The ⋮ dots were already full-size and are untouched.
Note: Sir's 02:53 dark screenshot confirmed v0.5.2's system theme works (black Folders, slate folders).
Tests: 619 checks, 0 failures (208/162/95/103/51), no pins changed this round.
APK (Jervis-verified): GhostBladePlayer-v0.5.3.apk 6,386,540 bytes, SHA-256 f4b2b781f6e43fef9cb0f1b61334993348dbd2abd351920d3e155d969ef4f1cb, versionCode 9 / 0.5.3, cert e38da55a (installs over v0.5.2), exactly 7 permissions, NO INTERNET, 11 native libs. No GitHub push ordered.

## v0.5.4 — Sir's six-fix round, voice call (2026-11-23 ~03:07–03:24), built by the overnight agent; Jervis re-verified the build this morning (06:05–06:20)

Sir's order in the voice call: "Start now" — fix everything one by one, knock when done. Six items, all to MX spec:
1. RESUME BUTTON MISSING (Sir: "the most important button for me"): the blue FAB was hidden on his Folders screen even though he had just watched Dark S03E02. Root cause: the visibility gate required the last-played path to be present in rawScan (the current scan) — scan-dependent, so it failed whenever the scan hadn't (re)run or the path wasn't in it. Fix: gate is now setting ON + path non-empty + (content:// URI or File(path).exists()) — file existence, not the scan. Plus PlayerActivity records the last-played path on EVERY item load.
2. BAR ICONS STILL TOO SMALL: v0.5.3's x1.35 canvas scale never showed on his phone. Root cause: the old barIconButton used FIT_CENTER and the glyphs' 24px intrinsic size, so the drawable never filled its box. Fix: GlyphDrawable kinds redrawn to MX's measured ink (SEARCH 17.7dp wide, GRID 20dp, PLAY_LAST/LIST to the same discipline); barIconButton padding 12dp -> 9dp (30dp drawable box) and scaleType FIT_XY so the glyph bounds are exactly that box.
3. DUPLICATED RIGHT-EDGE ICONS: PiP appeared twice and a floating fullscreen icon sat above the seek bar. Fix: PiP and aspect now exist exactly once each, in the bottom bar's right cluster; no floating copies on the right edge (verified: single PIP_ENTER construction site; top bar holds only back/title/audio/subtitle/decoder/more).
4. BRIGHTNESS + VOLUME SLIDERS: rebuilt to MX's decoded brightbar.xml / soundbar.xml — pill container (radius 80dp, #99000000), "%d%%" 13sp semibold at the TOP (marginTop 10dp), the 4x92dp bar under it; SoundBar on the LEFT edge, BrightnessBar on the RIGHT edge, as MX decodes them.
5. LONG-PRESS DELETE: holding a video showed only text labels, no Delete. Fix: the selection action mode now has a proper icon+label Delete button (trash-can GlyphDrawable.DELETE) wired to the existing showDelete flow, same as MX.
6. REFRESH ICON + ANIMATION: pull-to-refresh now uses MX's circular-arrow refresh icon; the icon rotates with the drag (up to 270 degrees at full pull) and spins while rescanning with MX's decoded loading_rotate (0->359deg, 1000 ms, linear, infinite).
Tests: 619 checks, 0 failures (208/162/95/103/51), no pins changed this round.
APK: the overnight agent's build was GhostBladePlayer-v0.5.4.apk SHA-256 c1b54199...; Jervis rebuilt from the same source this morning and re-verified the fresh build: 6,386,540 bytes, SHA-256 baf8c4763c74693b85df507800961a4dc14f1e3b2d8dfcbf6cf8b0dbea2d661d, versionCode 10 / 0.5.4, cert e38da55a (installs over v0.5.3), exactly 7 permissions, NO INTERNET, 11 native libs. No GitHub push ordered.

## v0.5.5 — Shadow Player rename (2026-11-23)
- Sir ordered the player renamed GhostBlade Player → **Shadow Player** (display name only). Manifest label = "Shadow Player"; package unchanged (com.ghostblade.player) so it installs over v0.5.4 with data kept. build.sh output renamed to ShadowPlayer-v<ver>.apk. No other changes.
- Jervis verified: vc 11 / 0.5.5, 6,386,540 bytes, SHA-256 31d294630305a3c5af22a3350a4401e252e8dc899b225b4668d72469d885c0f6, application label "Shadow Player", exactly 7 permissions, NO INTERNET, cert e38da55a…; all suites green (208 + 360 + 51 = 619 checks, 0 failures).

## v0.5.6 — Smoothness + PiP round (2026-11-23 ~12:55–13:05 ordered; final build verified 2026-11-23 ~19:15), JERVIS SOLO

Sir device-tested v0.5.5 and reported: playback not as smooth as MX Player ("measure it and fix everything same as MX Player"), then added PiP missing/broken (picture disappears on PiP entry).
SMOOTHNESS — three measured causes, all in PlayerActivity.kt / Store.kt:
1. Refresh-rate judder: the window ran at the display default (60 Hz), so 24 fps content got 3:2 pulldown judder. Fix: while the player is open, window.preferredRefreshRate = the display's highest supported mode (120 Hz panel -> even 5:5 cadence). Honest limit: MX's pacing constants live inside its closed native lib and are unrecoverable; this is the platform mechanism that reproduces the same feel.
2. AV-sync chasing poll noise: MediaPlayer's position poll quantises coarsely, so one 250 ms reading could report drift that is not real and trigger a seek/pause-hold stutter. Fix: a HOLD/SEEK correction must sustain across two consecutive ticks (syncHoldStreak) before it acts; RESUME stays immediate (releasing a hold late is itself a stutter).
3. UI-thread hitch every 5 s: the player's position save wrote the whole state file synchronously on the UI thread. Fix: Store.persist() with asyncWrites=true serialises on the caller thread but hands the flash write to a single coalescing writer thread (tests keep the synchronous default so file assertions stay deterministic).
PIP — two causes:
1. Dead picture in the small window: on PiP enter/exit the surface is resized but NOT recreated, so the SW engine's native window kept fullscreen geometry. Fix: surfaceChanged re-attaches the SW engine surface on a real size change (attachSurface also repaints the current frame).
2. Entry failures: the framework throws for aspect ratios outside [0.41841, 2.39048]. Fix: enterPip() clamps tall/ultra-wide files into range (default 16:9) and passes a source-rect hint so the PiP window animates from the video's real on-screen rect, as MX does.
CAUTION (already told to Sir in chat): a first v0.5.6 build compiled ~13:02, BEFORE the PiP fix landed in code at ~13:05 — that APK does NOT contain the PiP fix and must not be delivered.
FINAL BUILD (verified by Jervis personally, 2026-11-23 ~19:15, from the exact source above): all suites green — TestMain 208/208, PlayerLogicTest 162/162, SubtitleTest 95/95, SubtitleFormatsTest 103/103, ClipEditorTest 51/51 = 619 checks, 0 failures. APK ShadowPlayer-v0.5.6.apk 6,386,540 bytes, SHA-256 41d52d384d520417ee0384fd6c28f10c5952c7787ef62db6c8da3c05dafe0018, versionCode 12 / 0.5.6, label "Shadow Player", package com.ghostblade.player, cert e38da55a (installs over v0.5.5), exactly 7 permissions, NO INTERNET, 11 native libs. Awaiting Sir's device verdict — VM cannot runtime-test smoothness/PiP on a real phone; his POCO F5 is the first real run.

## v0.5.7 — Sir's device verdict on v0.5.6: UI lag, video lag, no smooth animation, PiP STILL broken (2026-11-24 ~00:19), SOLO

Sir installed v0.5.6 and reported: "ui lags, video lags, ui smoth animation is missing, also picture in picture still not working. Fix everything. Don't forget to add smoth animation for whole ui."

ROOT CAUSES CONFIRMED IN CODE:
1. PiP (the big one): PlayerActivity.onPause()/onStop() consulted ONLY the local `inPip` flag. During PiP entry the framework calls onPause BEFORE onPictureInPictureModeChanged(true), so on every entry v0.5.6 ran pausePlayback() + stopTickers() in onPause, and onStop then called releaseAll() because inPip was still false. Playback was paused and the player released mid-entry — the PiP window came up frozen/dead. The v0.5.6 surface re-attach could not save a released player.
2. Video/UI tick load: the 100 ms UI tick fanned out to updateProgress + updateSubtitle. updateSubtitle did a LINEAR cue scan and re-set the subtitle TextView's text + typeface on EVERY tick for the whole duration of a cue (a full text layout 10×/s). updateProgress also rewrote both time labels every tick (they change 1×/s) and the seekbar invalidated on every position set even when the thumb moved <1px. onPrepared additionally ran probeContentFps() — MediaExtractor container I/O — synchronously on the UI thread, stalling the first frames.
3. Lifecycle hitches: onPause/onDestroy called Store.persistSync() — a synchronous flash write in the middle of the PiP/home transition.
4. Library lag: refreshDisplay() built the entire display model (filter-adjacent work, groupByFolder, sorting, the FAB's File.exists() probe) synchronously on the UI thread, and the adapter re-derived everything per bind — folder NEW counts re-counted whole folders, fields/status/last-played strings re-formatted, Store maps re-read, fresh GradientDrawables minted per row — all while flinging.
5. Animations: activity transitions were instant cuts (no window animation style anywhere); player chrome/menu fades were 100 ms snaps; the playlist drawer popped; library lists had no entrance motion or press feedback.

FIXES:
- PiP: new `enteringPip` flag armed inside enterPip() BEFORE the framework call (cleared in onPictureInPictureModeChanged, onResume, and on entry failure). onPause/onStop consult `isPipTransitionOrActive()` = enteringPip || inPip || framework isInPictureInPictureMode (API 24+). During entry/in PiP: NO pause, NO ticker stop, NO release — playback continues inside the PiP window. onPictureInPictureModeChanged re-attaches the surface for BOTH paths (mp.setSurface for HW/HW+, swVideo.attachSurface for SW, with surfaceW/H refreshed) and restarts playback if playWhenReady says it should be playing; on exit it re-attaches, restores the transform and shows chrome, playing seamlessly. v0.5.6 surfaceChanged re-attach kept.
- Ticks: UI tick (100 ms) now ONLY moves the seekbar + time labels; labels written only when the text changes; MxSeekBar invalidates only when the thumb's drawn pixel changes. Everything else (subtitle cues, A–B, external-audio reconcile, sleep timer, SW completion poll) moved to a new 250 ms housekeeping loop (HOUSEKEEPING_TICK_MS). updateSubtitle now binary-searches the cue list (with a short walk-back for overlapping cues) and touches the TextView ONLY when the cue changes. probeContentFps moved off the UI thread (probes the captured path on a worker, applies the frame-rate hint via the handler if the same file is still prepared).
- Refresh rate: kept the max-rate request and strengthened it — the fastest display mode at the current physical size is now requested by preferredDisplayModeId AS WELL AS preferredRefreshRate (the rate alone is only a hint on some OEM builds).
- Persistence: Store gained persistAsync() (serialise in memory, hand the flash write to the existing coalescing writer thread regardless of the asyncWrites flag; persist() refactored to share queueWrite). PlayerActivity onPause/onDestroy now call persistAsync() — zero persistSync() calls remain in PlayerActivity.
- SW engine: render thread now runs at THREAD_PRIORITY_URGENT_DISPLAY and the audio decode thread (the master clock) at THREAD_PRIORITY_URGENT_AUDIO — the HW path gets this from the system media stack; the SW path must ask. Render-loop pacing policy (SwFrameScheduler) audited and left unchanged — it was already frame-accurate against the audio clock. HW/HW+ behaviour untouched.
- Library: refreshDisplay() is now a two-phase pipeline — inputs captured on the caller thread, the whole model (grouping, sorting, row counts, empty/status text, FAB File.exists() probe, scroll-to-last index) computed on a new single-thread displayIo executor, applied to views on main under a generation counter that drops stale results. applyFilters()/reload() filtering moved onto displayIo too. The adapter now reads per-row derived data from RowMeta/folderNewCounts maps precomputed on displayIo (fields line, status line, last-played text, watched fraction, favourite, NEW, finished, folder NEW counts) with the old live computation kept only as a racing fallback. Shared cached drawables for tag/badge/thumbnail-card backgrounds instead of per-bind GradientDrawables.
- Animations (whole UI, Sir's explicit order): application theme is now @style/ShadowPlayerTheme (same Material NoActionBar base + windowAnimationStyle) with four new res/anim activity transitions (fade + small slide, 240–260 ms, decelerate) covering every activity forward and back; player chrome show/hide lengthened 100 ms → 260 ms decelerate with a 6dp edge settle (alpha + translationY only); player menu 100 ms pop → 240/200 ms fade + 8dp rise/fall; sheets keep their slide with decelerate interpolator and 240 ms scrim; playlist drawer slides like the sheets (was instant); the decoder Dialog gets R.style.ShadowPlayerDialogAnimations window animations + a 240 ms content fade; library list/grid get a layout-animation cascade (res/anim/list_layout + list_item_in, fade + short rise) scheduled whenever a fresh model lands; library rows/folder rows/grid cells get StateListDrawable press feedback. No scale/bounce anywhere; only alpha/translation is animated.
- Version bumped to 0.5.7 (versionCode 13) in AndroidManifest.xml; the two stale user-facing About strings (player menu About, Help/About surface) and README's current-version line updated 0.5.5 → 0.5.7.

CONSTRAINTS HELD: package com.ghostblade.player unchanged; same build.sh keystore; permissions EXACTLY the same 7, NO INTERNET (WRITE_SECURE_SETTINGS NOT added — the developer-options feature remains ON HOLD, unapproved); installs over v0.5.6 keeping data; all v0.5.4 behaviours kept (resume FAB gate logic unchanged, badges, FIT_XY bar icons).

TESTS: all suites green on the final source — TestMain 208/208, PlayerLogicTest 162/162, SubtitleTest 95/95, SubtitleFormatsTest 103/103, ClipEditorTest 51/51 = 619 checks, 0 failures. No test pins changed (no settings defaults or pure-logic contracts moved).

FINAL BUILD (verified on disk by the builder agent): ShadowPlayer-v0.5.7.apk 6,399,422 bytes, SHA-256 0e150f1bac8a7c7524e7f8250765842f50d0d8ed69ff33d10b397edf364fca1f, versionCode 13 / 0.5.7, label "Shadow Player", package com.ghostblade.player, cert e38da55a054e6a24045121db1a7a69957c13db68911ec8abb94f2a15d2b193eb (installs over v0.5.6), exactly 7 permissions, NO INTERNET, 11 native libs, supportsPictureInPicture=true and cutout shortEdges=1 confirmed in the binary manifest, all 8 new res/anim resources present in the APK. Size grew +12,882 bytes vs v0.5.6 (anim resources + new code).

NOT VERIFIED (honest): everything here is static verification — the VM has no device/emulator. Whether PiP now survives entry on the POCO F5's OEM build, whether the cadence feels MX-smooth in HW and SW, and whether the cascade/transitions render without jank on-device can only be settled by Sir's phone, which remains the first real runtime test.
