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
