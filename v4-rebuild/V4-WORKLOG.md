# GhostBlade Player v0.4 — MX Carbon-Copy Rebuild Worklog

Coordinator: Jervis-delegate (v0.4 rebuild coordinator). Binding spec: ~/workspace/projects/mxplayer-analysis/MASTER-SCENE-SPEC.md.
Started: 2026-11-22 ~14:46 +06. Baseline: v0.3 (versionCode 4) + SW engine source milestone (619 JVM checks green at engine landing).

## Shadow Monitoring — audit (2026-11-22, read-only, independent)

**Verdict: PASS-WITH-NOTES — fit for Jervis's personal verification and delivery to Sir.** No P1. No faked feature found. Every coordinator claim I tested reproduced exactly.

### Independently verified (not taken from team reports)
- **APK facts:** GhostBladePlayer-v0.4.apk **6,382,363 bytes, SHA-256 28b4e93c3956b79a3ca11e725340dd1aefdbf0a9c2b2f93ae53446a36b7850a0, versionCode 5 / 0.4, minSdk 24 / targetSdk 34** — exact match. **Exactly the same 7 permissions, NO INTERNET, no location.** Signer cert **e38da55a054e6a24045121db1a7a69957c13db68911ec8abb94f2a15d2b193eb** (installs over v0.3). **Cutout shortEdges=1** in the binary manifest; PiP flag true. **11 native libs** under lib/arm64-v8a/ (6 FFmpeg + libass/libfreetype/libfribidi/libharfbuzz/libgbass); `libgbass.so` DT_NEEDED → `libass.so` (real linkage, not a stub).
- **Suites re-run by Monitoring** (full-source route, each suite compiled separately): **TestMain 208 · PlayerLogicTest 162 · SubtitleTest 95 · SubtitleFormatsTest 103 · ClipEditorTest 51 = 619 checks, 0 failures** — exact match to the claim.
- **§S1 render path:** `TextureView` survives only in two explanatory comments — zero class usage. Player video layer is a code-created `SurfaceView` (PlayerActivity.kt:2235); HW via `MediaPlayer.setSurface`, SW via `SwVideoSession.attachSurface` → single `nativeVideoSetSurface` JNI → `ANativeWindow_fromSurface` + native render loop in gbffmpeg.c. **100 ms UI tick** (`UI_TICK_MS = 100L`); the only remaining 500L delays are headset hook multi-press dispatch (unrelated). **AvSyncPolicy ticker posts only when `engineActive && !swMode`** — HW+ only, as certified.
- **libass honesty:** `supportsAssRendering()` returns a flag set by actually attempting `System.loadLibrary` on the 5-lib set in order — a real probe, not a constant. `assRenderFrame` has **zero callers** outside FFmpegEngine.kt: the coordinator's "engine capability shipped, player overlay NOT wired" disclosure is exactly true; ASS files still play as parsed-text cues (v0.3 behaviour, no regression, nothing in the UI claims styled ASS).
- **Gates:** engine `gateReason` retains exactly 4 gates, each technically true (PCM-only decode; no libavfilter in the APK — confirmed in the lib list; RGBA8888-only SW render; AudioTrack-only). The two ungated keys check out: speedup-tricks/allowlist consumers exist at the SW open (PlayerActivity.kt:785, FFmpegEngine open signature). Stage's claimed consumer gaps are real: `g_sub_zoom` is declared in PSet (PlayerActivity.kt:618) and read nowhere — its gate honestly stays.
- **Cuts:** no Cast/MediaRouter/ad-SDK/payment/tracker identifiers anywhere in source or native code; the single http string in the DEX is a Kotlin-stdlib youtrack message. Manifest carries exactly the 7 permissions.
- **Step 0 counts exact:** 1,482 colors · 1,390 dimens · 25 integers · 22 styles · 24 ic_mx_* drawables. build.sh packages the libass set from codec-lab (reproducible). No legitimate Mulish exists on this VM outside MX's decoded files — the font PARTIAL is honest.

### Scene grades (Monitoring)
| Unit | Grade | Note |
|---|---|---|
| Step 0 Foundation | PRESENT | font PARTIAL inside it (declared, honest) |
| Step 1 Engine | PRESENT / libass PARTIAL | surface, §S3 events, sync, clock all verified; ASS = engine-only |
| P1 Player | PRESENT | SurfaceView swap + decoded transport verified at source |
| P2 Drawer | PARTIAL | decoded rail landed, grid correctly deleted; panel 300dp vs decoded 200dp (documented legibility deviation) |
| P3 Subtitle panel/bars | PARTIAL | panel + floating bars landed; ASS bitmap overlay not consumed |
| P4 Audio panel | PRESENT | content/reachability preserved in drawer structure |
| P5 Decoder dialog | PRESENT | decoded Cancel/Done footer adopted; row geometry retained + disclosed |
| P6 OSDs | PRESENT | 1,000 ms self-hide, BrightnessMap 1.8 / min 0.05 / 255 / % label |
| L1 Library | PARTIAL | decoded geometry + pull-to-refresh landed; adapter internals not established (spec's own open RE item — do-not-guess) |
| M1 Music | PARTIAL | Favourites/Recent/.lrc/widget transport real; no detail activities, no dedicated music service, widget cold-start caveat |
| S-SET Settings | PARTIAL | decoded presentation over all 123 nodes; root icons pending; every gate re-verified honest |
| X1–X7 | PRESENT (X6 PARTIAL inside) | X6 stream-copy by design with in-app disclosure; X1 email PIN recovery correctly CUT (internet) |

### The 7 declared PARTIALs — judged
All seven are **honest**, each verified at source level, none hiding anything beyond its stated reason: (1) ASS player-overlay wiring — the most material of the seven (engine can render styled ASS; the user still sees text cues), declared in two places, no UI claim contradicts it; (2) library adapter internals — the spec itself forbids guessing them; (3) Mulish font — no legitimate source exists on the VM; (4) music depth — gaps named precisely; (5) RecyclerView hosts — Sir's own no-AndroidX architecture ruling, geometry replicated, internal-only difference; (6) settings root icons — small cosmetic gap; (7) X6 clip editor — by-design stream copy, disclosed in the app itself. Remaining P3s: P2 panel-width deviation, SurfaceView mirror/flip caveat on very old devices, widget service-PendingIntent cold-start caveat, one stale manifest comment about widgets (cosmetic).

**Caveat (standing):** all verification is static + JVM — no emulator/device exists on this VM. SW playback smoothness, on-device libass loading (link-verified, not device-run) and SurfaceView rendering can only be confirmed on Sir's POCO F5.

**Overall programme: 95%** — rebuild complete as declared, Monitoring audit complete; Jervis's personal verification + delivery to Sir remain.

## Coordinator rulings (documented, honest)
- **No AndroidX in this app** (framework-only Kotlin, manual aapt2/kotlinc pipeline — Sir's standing architecture). MX's RecyclerView hosts (quick strip, library) are therefore rebuilt as framework views at MX's decoded geometry/item layouts. PARTIAL with this reason wherever the spec says RecyclerView.
- **Icons are redrawn** from ICON-CATALOGUE.md measured geometry — never MX's asset files. Font: MX's Mulish files are not lifted; OFL Mulish used only if legitimately available on the VM, else documented PARTIAL.
- **libass**: Engine Team attempts a timeboxed NDK build (fribidi+FreeType+libass). If it does not land cleanly, ASS stays parsed-to-text and the gap is recorded as PARTIAL with the precise blocker.

## Team roster
| Team | Files owned | Agents |
|---|---|---|
| Shadow Engine Team | native/gbffmpeg.c, FFmpegEngine.kt | 1 |
| Shadow Foundation Team | res/, build.sh (res only) | 1 |
| Shadow Player Team | PlayerActivity.kt | 1 |
| Shadow Library Team | MainActivity.kt, Library.kt, FileOps.kt | 1 |
| Shadow Stage Team | SettingsActivity.kt, Settings.kt, Surfaces.kt, Widgets.kt, ClipEditor*.kt, Subtitle*.kt | 1 |
| Coordinator | integration, build, verification | 1 |

## Scene status (13 units)
| # | Unit | Status | % |
|---|---|---|---|
| 0 | Resource foundation (Step 0) | done | 100 |
| 1 | Engine parity — surface/callbacks/libass (Step 1) | done — surface path verified + EOF/error defect fixed, §S3 events landed, sync certified (ticker deletable for SW, stays HW+), libass built in lab; packaging + player overlay wiring at integration | 90 |
| 2 | P1 Player scene + render-path swap (Step 2) | done — SurfaceView swap, 100 ms loop, decoded transport/seek/strip; setFixedSize deliberately unused (documented) | 95 |
| 3 | P2 Player drawer/menu | done — decoded right rail landed, recording-era grid deleted (not in decoded layouts), all items re-homed; panel 300dp vs decoded 200dp (documented) | 90 |
| 4 | P3 Subtitle panel + speed/sync bars | done — decoded bottom panel + floating bars landed; ASS bitmap overlay consumption in player NOT wired (engine capability shipped, see unit 1 note) | 90 |
| 5 | P4 Audio panel | done — content/reachability preserved inside drawer structure | 90 |
| 6 | P5 Decoder dialog | done — decoded Cancel/Done footer + padding adopted | 90 |
| 7 | P6 OSDs (supreme/TapSeek/bars/lock/zoom) | done — 1,000 ms self-hide, BrightnessMap 1.8/min 0.05/255/% label | 95 |
| 8 | L1 Video library (Step 4) | done — decoded row/grid/toolbar geometry, pull-to-refresh landed; adapter internals PARTIAL (not established by decompile) | 90 |
| 9 | M1 Music scene (Step 5) | partial — Favourites/Recent tabs, .lrc lyrics, real widget transport landed; MX lyrics is an online WebView (cut) | 60 |
| 10 | S-SET Settings (Step 6) | done — decoded presentation over all 123 nodes; gates re-verdicted, none falsely ungated; root icons await res drawables | 90 |
| 11 | X1–X7 Secondary scenes (Step 7) | done — decoded-layout pass landed; X1 email PIN recovery cut (internet), X6 partial by design (stream-copy) | 85 |
| 12 | Step 8 — build v0.4, suites, audit | done — GhostBladePlayer-v0.4.apk built + coordinator-verified; Monitoring audit is the parent's next step | 100 |

**Overall: 90%** (all 13 units landed; the remaining 10% is the documented PARTIAL list in the coordinator's final report — ASS player-overlay wiring, adapter internals, font, music depth, RecyclerView hosts.)

## Test counts
- Baseline (engine milestone): 619 checks, 0 failures.
- **Final v0.4: 619 checks, 0 failures** — TestMain 208 · PlayerLogicTest 162 · SubtitleTest 95 · SubtitleFormatsTest 103 · ClipEditorTest 51, all re-run by the Coordinator via the rewritten tests/run-player-tests.sh (now a self-contained full-source route; the stale subset runner is fixed) + tests/run-tests.sh. Plus Engine harness 31/31 and the Stage Team's 5/5 LRC spot-check assertions.

## Stage Team — done notes

Scope: SettingsActivity.kt, Settings.kt, Surfaces.kt, Widgets.kt, ClipEditor.kt, ClipEditorActivity.kt, Subtitle.kt, SubtitleFormats.kt. No node dropped (123-node store untouched), no new permissions, NO INTERNET, framework-only.

### Per-scene status
- **S-SET Settings — LANDED (presentation) / gates PARTIAL (below).** Decoded presentation adopted: hairline dividers between rows (the existing `divider()` was defined but never used), category headers restyled to divider_preference.xml verbatim (14sp bold, accent, 12dp padding), row shell to preference.xml geometry (15→16dp start margin, 6dp vertical margins, 64dp min height under the ?android:listPreferredItemHeight token). PARTIAL: MX's root screen carries per-screen icons (preference_header.xml, Settings catalogue group) — icon assets are the Foundation Team's res/ programme; rows remain text+chevron until those land.
- **M1 Music — PARTIAL.** Landed: Favourites tab (MX MusicFavouriteActivity equivalent; shared Store favourite set, long-press track menu → Add/Remove favourite + Lyrics), Recently Played tab (MX RecentlyPlayedActivity equivalent; Store.historyRecords joined to the audio scan), .lrc lyrics — new pure `SubtitleFormats.parseLrc` (multi-tag lines, [offset:], metadata skipped, JVM-verified) rendered as clean timed lines in a scrollable lyrics view (MX's LyricsActivity is an online-fed WebView — CUT with internet; local sidecar is the honest equivalent). Playlists/albums/artists/folders/detail already existed and were kept. Widgets: real transport LANDED-with-caveat — when PlaybackService is declared (it is, in the manifest), ▶/⏮/⏭ now send ACTION_TOGGLE/PREV/NEXT service PendingIntents routed to the live player's transport; the old start-the-queue activity intents remain as the not-declared fallback. PARTIAL reason: a service PendingIntent uses startService semantics, so a tap while the service is not running can be dropped by background-start limits instead of cold-starting playback. Remaining PARTIAL: no dedicated album/artist/folder *detail activities* (in-surface group drill-in instead), no music-specific playback service (playback rides PlayerActivity + PlaybackService).
- **X1 Private folder + PIN — LANDED (pass).** Existing PIN set/verify/change + list/grid + selection actions verified against fragment_private_folder_verify/set_up layouts; presentation already on the shared MX-geometry chrome (56dp bar = decoded dp56 toolbar). PARTIAL: MX's email-based PIN recovery is an internet/account feature — CUT, not faked.
- **X2 Recycle bin — LANDED (pass).** Menu order, restore/delete flows already match dialog_recycle_bin_action_confirm structure; shared chrome/row geometry.
- **X3 Playback history — LANDED (pass).** Newest-first records with resume/finished state; clear-history confirm present.
- **X4 Media cleaner — LANDED (pass).** Duplicates/large/empty-files/empty-folders sections over Cleaner logic; deletes route through the recycle bin.
- **X5 USB list — LANDED (pass).** Single "Refresh" menu item per decoded list_usb truth; honest unresolvable-volume path via the system picker.
- **X6 Clip editor — PARTIAL (by design).** Adopted from view_edit_player_view.xml: black preview backing (MX's SurfaceView sits in a black 8dp-radius card) and the card_time_info pill (32dp-class, #80171717, 12sp white "pos / selected") replacing the plain labels. NOT adopted: MX's preview surface is a literal SurfaceView and its editor is frame-accurate — ours is VideoView + stream copy; the keyframe-cut disclosure stays in the UI note verbatim.
- **X7 About/Help — LANDED (pass).** HelpActivity (What's New / Features / FAQ / About) already offline-static; MX's update-check/bug-report entries stay CUT (internet).

### Gate-by-gate ungate verdicts (re-checked against source, 2026-11-22)
Engine gates (FFmpegEngine.gateReason — engine team's truth, verified): `prefer_audio_passthrough_mode` KEEP (engine decodes to PCM, no bitstream path) · `default_deinterlacer` KEEP (no libavfilter in the LGPL build; SW video unfiltered) · `color_format` KEEP (SW renders RGBA8888 only) · `audio_device` KEEP (AudioTrack only, no OpenSL ES) · `use_speedup_tricks` ALREADY UNGATED by the engine team (SwVideoSession consumes it — verified, no Settings change needed) · `omx_video_codecs.3` ALREADY UNGATED (SW allowlist consumes it — verified).
Local gates (SettingsActivity LOCAL_GATE_REASONS): **no gate could be honestly removed in full** — every remaining gated key was grep-verified to have no consumer. Three reasons were REWRITTEN because the old DECODER_GATE framing predated SW video: `swdecoder_local` (SW works via Default decoder → SW; the per-scope switch itself has no reader), `swdecoder_net` (offline build — no network scope exists), `omx_audio_with_sw_video` (SW session decodes A+V together; no HW+-audio/SW-video mix). All other local gates KEEP with reasons spot-verified still true, incl. `gesture.subtitle_zoom` (PSet.gSubZoom exists but PlayerActivity never reads it — Player Team consumer gap, exact key: `gesture.subtitle_zoom` → PSet `g_sub_zoom`) and `tune_subtitle_border_color` (player stroke is fixed black shadow layers; PSet has no border-colour field — Player Team gap).

### Subtitle style truth for the Player Team (in Subtitle.kt `SubtitleStyleTruth`)
Decoded (RESOURCE-TRUTH/dimens): default bottom padding **8dp** (`default_subtitle_bottom_padding_dp`), default background **#88000000** (`default_subtitle_bkcolor`), shadow colour **#A6000000** (`exo_subtitle_shadow_color`). Text size: no decoded SubView dimen exists — 18sp default is ours. Stroke width/colour: **not established** in decoded values (SubView is a StrokeView subclass but no constant ships in dimens/colors) — the player's setShadowLayer radii (3.5f/1.5f/2f, black) stay a documented approximation; do not invent a number.

### Compile evidence
Full-source kotlinc (all 15 app .kt files, android-34 android.jar + last-known-good build/classes for R): **EXIT 0**, warnings only (pre-existing deprecations). No foreign-team failures. JVM spot-check of the new parser: `SubtitleFormats.parseLrc` — 5 assertions PASS (offset applied, multi-tag line → 2 cues, next-cue end chaining, last cue +4s, untagged input → empty).

### Flags for coordinator / other teams
- AndroidManifest.xml widget comment ("there is no playback service") is stale — PlaybackService exists and widgets now use it (manifest is not Stage-owned).
- Player Team consumer gaps: `g_sub_zoom` unread; no subtitle border-colour PSet field. Either would let two more gates drop.

## Milestone notes
- 14:46 +06 — Coordinator read MASTER-SCENE-SPEC in full; surveyed source (PlayerActivity 7,185 lines; engine already exposes SwVideoSession.attachSurface + nativeVideoSetSurface; ticks currently 500/200/250 ms; auto-hide default 3,500 ms vs MX 2,000 ms; transport 64dp slots vs decoded 50dp×46dip). Teams dispatched.
- ~15:5x–17:0x +06 — All five teams reported (Foundation → Stage → Library → Engine → Player). Coordinator integration: build.sh ships the libass .so set (fribidi/freetype/harfbuzz/ass/gbass, DT_NEEDED-verified), manifest bumped to versionCode 5 / 0.4, tests/run-player-tests.sh rewritten to the full-source route (stale subset list retired).
- Integration build: **GhostBladePlayer-v0.4.apk — 6,382,363 bytes, SHA-256 28b4e93c3956b79a3ca11e725340dd1aefdbf0a9c2b2f93ae53446a36b7850a0**, versionCode 5 / 0.4, exactly the same 7 permissions, NO INTERNET, cutout shortEdges=1 in binary manifest, apksigner cert e38da55a…, 11 native libs under lib/arm64-v8a/ (6 FFmpeg + 5 libass set). Coordinator personally re-verified every fact. BUILD-COMPLETE (not delivered — Monitoring + Jervis verification follow).

## Engine Team — done notes

- **SW surface path (§S1) VERIFIED at source level**: open → attachSurface (single `nativeVideoSetSurface` entry, native owns the ANativeWindow, geometry = display size, RGBA8888, repaint-on-attach) → start(clock) → seek (keyframe + decode-and-discard landing, no black flash) → close (render thread joined before native free). HW+ audio and probe() untouched this round — unregressed by construction. Full contract: v4-rebuild/ENGINE-API.md.
- **Defect fixed (FFmpegEngine.kt only)**: hard decode error was conflated with EOF — both set `ended`, so a broken stream read as a clean completion. Error is now a distinct terminal state (`hasError()`); `isEnded()` semantics unchanged for existing consumers.
- **Event callbacks (§S3) LANDED, additive**: `SwVideoEvents` codes in MX's what-discipline (1 prepared / 2 completion / 4 seek-complete / 5 video-size / 100 error). `SwVideoSession.setListener(...)` push delivery (size re-delivered on late registration; listener exceptions swallowed) OR `pollEvent()` FIFO drained at the 100 ms tick while no listener is set. Completion/error fire once per open/seek epoch. Native side needed no changes — the peek sentinels already carried the state.
- **Clock poll (§S2) certified**: `SwAudioSession.clockMs()` (AudioTrack head + one mutex-guarded JNI int read) is the cheap 100 ms master-clock poll for both HW+ and SW; no new clock entry needed. `videoPeekPts` is render-loop-only (it decodes) — never a UI position poll.
- **A/V sync (§S4) certified**: SW path sync is inside the engine (audio clock master; SwFrameScheduler show-at-pts / drop-only->100 ms-late / prefetch in ≤10 ms wait slices). The 250 ms AvSyncPolicy ticker is **deletable for the SW path**; it must STAY for HW+ (it slaves MediaPlayer, whose clock the engine cannot schedule).
- **libass (§S7) LANDED in the lab**: FriBidi 1.0.16 + FreeType 2.13.3 + HarfBuzz 10.4.0 + libass 0.17.3 built for arm64 API 24 (NDK r27c, meson; recipe + artefacts in codec-lab/libass-lab/, out/lib ≈ 2.95 MB of .so). Minimal JNI shim libgbass.so (10 KB, assjni.c) links clean and exports the two declared natives. Engine integration: `FFmpegEngine.supportsAssRendering()` runtime probe + `assRenderFrame(...)` → RGBA ByteArray; **flag defaults OFF** — it turns true only when the app build ships the .so set (packaging is outside Engine Team files). PARTIAL aspects, honestly: no fontconfig/system font provider (embedded/added fonts only — a default-font registration entry is the follow-up), and the set is link-verified, not yet device-run.
- **Tests run**: FFmpegEngine.kt standalone compile vs android-34 android.jar — clean. Engine harness (scheduler policy, codec allowlist, new event API on an unopened session, libass flag absent-libs behaviour): **31 passed, 0 failed**. Full JVM suite route (tests/run-player-tests.sh) is currently BLOCKED by the Player Team's in-flight PlayerActivity.kt — 31 compile errors, all inside PlayerActivity.kt (unresolved gridVisible/gridPanel/gridScrim/ClipEditorActivity), zero in engine files; suites to be re-run at integration.

## Foundation Team — done notes
- **Status: Step 0 DONE** (2026-11-22, Shadow Foundation Team). No .kt/native file touched; build.sh unchanged (existing aapt2 compile+link pipeline handles the new res/ as-is — no res-handling change was strictly needed).
- **Values transplant** (MX names verbatim, exact decoded values from RESOURCE-TRUTH §2–§5 / decoded res/values; OFFLINE surfaces only — player, library, music, settings, dialogs; OTT/ads/Cast/games/payments/share/TV excluded):
  - `res/values/mx_colors.xml` — **1,482 colors**
  - `res/values/mx_dimens.xml` — **1,390 dimens** (dp/sp scale + player chrome: `screen_subnavibar_height=46.0dip`, transport buttons `dp50`, seek/dot `dp4`, sheet/panel, library row/grid, thumbnail geometry)
  - `res/values/mx_integers.xml` — **25 integers** (24 decoded verbatim incl. `animation_default_duration=300`, `interactive_anim_duration=100`, `bottom_sheet_slide_duration=150`; plus `mx_interface_auto_hide_delay_default=2000`, the SharedPreferences default from MASTER-SCENE-SPEC §S5 given a resource home — not an MX integer resource, labelled as such in the file)
  - `res/values/mx_styles.xml` — **22 styles**: `ToolbarTitleText` (14sp), `MXThemeToolbarTitleBase`/`MXToolbarTitleDefault`/`MXToolbarTitleDark`/`MXToolbarTitleAurora`, `ToolbarTitleStyle`, `PlayerToolbarTitle`, `MxText` family (6), `MxOne*` dialog text appearances (7), `MxPlaybackTheme` (framework items of `Base.Playback.Dark`), `MxBottomSheetModal`. AppCompat parents/attrs cannot link in this framework-only app, so parents are the closest framework styles and AppCompat-only items are dropped — noted per style in the file.
  - Total values entries: **2,919**.
- **Vector drawables: 24 created** in `res/drawable/`, all named `ic_mx_<catalogue name>`, each a new XML redrawn from the catalogue's full verbatim pathData (no MX PNG/WebP/XML asset file copied): ic_mx_exo_controls_fastforward, ic_mx_exo_controls_rewind, ic_mx_ic_ai_smart_enhance, ic_mx_ic_lock, ic_mx_ic_vol_mute, ic_mx_ic_vol_unmute, ic_mx_abc_ic_search_api_material, ic_mx_bg_rectangle_clip_4_sides_selected, ic_mx_ic_default_cancel, ic_mx_mxskin__ic_action_history__light, ic_mx_mxskin__mx_new_voice_search__light, ic_mx_abc_ic_menu_share_mtrl_alpha, ic_mx_abc_ic_clear_material, ic_mx_cw_play, ic_mx_ic_close, ic_mx_ic_exit_smile, ic_mx_ic_mxtube_loading, ic_mx_material_ic_menu_arrow_down_black_24dp, ic_mx_mxplayer_logo_12, ic_mx_mxplayer_logo_12_dark, ic_mx_mxskin__bg_rectangle_clip_4_sides__light, ic_mx_mxskin__bg_rectangle_nocolor_clip_4_sides_border__dark, ic_mx_mxskin__ic_action_close__dark, ic_mx_mxskin__ic_action_close__light. No vectors fabricated for icons without pathData.
- **Icon geometry table:** `v4-rebuild/ICON-GEOMETRY.md` — all **146 Player chrome** + **187 Video library** catalogue entries with type, derived dp size and per-density measured px, for the code-drawn icons.
- **Font: PARTIAL.** No legitimately usable Mulish/Muli exists on this VM: system fonts are DejaVu/Liberation/Noto/LibreOffice/LyX only, and the only Muli TTFs anywhere on the VM are MX's own decoded files (`decoded/res/font/muli_*.ttf`), which must not be taken. Nothing downloaded (offline rule). Default font stands; `fontFamily` items are omitted from mx_styles.xml and flagged there.
- **Verification:** `aapt2 compile --dir res` exit 0 and `aapt2 link` against AndroidManifest.xml + android-34 android.jar exit 0 (build-tools 35.0.0), producing a linked test APK (198,212 bytes, /tmp — not shipped); `aapt2 dump resources` shows 3,006 color/dimen/integer/style/ic_mx drawable entries. Full ./build.sh not run: it rebuilds all teams' in-flight Kotlin/native, which is the Coordinator's integration step.

## Library Team — done notes

Scope: MainActivity.kt, Library.kt, FileOps.kt only. All geometry below is from the decoded layouts in `mxplayer-analysis/decoded/res/layout/` + styles/colors in `decoded/res/values/` (NewText.* styles, grid colours), cross-checked against RESOURCE-TRUTH.md and ICON-CATALOGUE.md (Video library group). v4-rebuild/ICON-GEOMETRY.md did not exist when this work ran — catalogue values were used directly. Library.kt and FileOps.kt needed no changes (no UI geometry lives there; all their features are exercised unchanged by the rebuilt UI).

### Landed vs decoded truth

- **Spec correction found in decode:** the mined "thumbnails 144×84dp class" is NOT what the decoded layout says. `list_row_media.xml` icon_frame is **96×56dp**; the old 144×84 thumb was replaced with 96×56.
- **List media row (`list_row_media.xml`):** container padding L14/T8/B8, no right padding; thumb in a 4dp-corner card (CardView radius 4dp approximated by a rounded black background + clipToOutline — PARTIAL: no CardView elevation in framework-only code); centred type icon under the thumb (32dp); title **14sp semibold** (NewText.Medium.Primary), maxLines **3**, lineSpacingExtra 4dp, marginBottom 2dp (was 16sp/2 lines); text block marginStart 8dp; `tv_last_played` line added (12sp secondary, 1 line, from Store.lastPlayedAt); `rl_info` row = 12dp folder-type icon + fields text 12sp secondary, marginStart 2dp, maxLines 2, marginBottom 4dp; status line retained below as the stand-in for MX's `file_info` Flexbox row; more button rebuilt to decoded geometry — **40×38dp, padding T10/R22/B10, 18dp vertical-dots glyph** (was a 34dp cyan circle with a text glyph; the cyan circle exists nowhere in MX's decoded layouts).
- **Grid media cell (`list_grid_media.xml`):** item padding T4/B4; card spans cell width at decoded ratio **1:0.564** via a measuring FrameLayout (framework stand-in for the constraint ratio); centred type icon 40dp; title 14sp semibold, marginTop 4dp, marginEnd 18dp, maxLines 2, **minLines 2**, lineSpacingExtra 4dp; more button **38×28dp, padding L20/B10**, 18dp dots glyph at the title's top end. MX's grid item has no fields line — removed from grid (fields remain in list view + Properties; nothing became unreachable).
- **Tags on the thumb (both layouts):** NEW tag — round_box_small (2dp corners, 4dp horizontal padding), **tag_red #F2405D** background (was MX-blue), 10sp semibold white (NewText.Medium.Tag), 4dp top/start margins. Duration tag — same box, 10sp semibold white, 4dp bottom/end margins, tint **duration_background #80171717 in list / tag_dark #CC000000 in grid** (was a flat #99000000 in both). Favourite heart (iv_favourite) now drawn when a video is favourited: 16dp list / 24dp grid, #F2405D, 2dp top/end margins.
- **Folder rows (`list_row_listable.xml`):** folder icon **62dp** (@dimen/directory_width; was 40dp), marginLeft 16dp; text block 16dp from icon and from end; title **15sp** regular primary (NewText.Primary; was 16sp medium), maxLines 2, lineSpacingExtra 4dp, marginBottom 2dp; count 12sp secondary; total size shown as the `info_opposite` round-box tag beside the count (10sp semibold, gridBox colours #4DDADDE4/#DADDE4 dark, #F2F6FA/#96A2BA light); `info_normal` 12sp secondary single line below (marginTop 2dp) carrying the folder path, with the retained "▶ last played" marker prefixed when applicable; `iv_new` 10dp red dot (#F2405D) at the icon's top end (marginTop 11dp) when the folder holds a NEW-tagged file.
- **Folder grid cells (`list_grid_listable.xml`):** 62dp icon centred with 8dp side margins; title centred, 15sp primary, maxLines 2, 8dp side margins; count centred 12sp secondary, marginTop 2dp, marginBottom 8dp. (Previously grid mode reused the list folder row.)
- **Text colours (decoded skins):** primary #DADDE4 dark / #35344C light (= existing theme.title, verified against colors.xml); secondary **#99DADDE4 dark / #96A2BA light** — our theme.secondary (#85929C dark) did NOT match MX and is no longer used for library row text; more-glyph tint = listMoreColor (#DADDE4 / #96A2BA).
- **Selection / action mode:** selected mark is now `ic_file_selected` geometry — **24×24dp** at the thumb's top end (list layout offsets it 68dp from start + 4dp top inside the 96dp frame = 4dp from the end; grid 4dp margins), redrawn as an MX-blue disc + white check; selected rows/cells take MX's checked background from the bg_item selector (**grid_selected_background #26FFFFFF dark / #F1F1F1 light**). Selection action bar height 52dp → **56dp** (?actionBarSize, toolbar_actionmode.xml). Action-mode background colour itself is theme-attr only in MX — not established, black retained.
- **Toolbar (`toolbar.xml`):** bar already ?actionBarSize = 56dp; title corrected to **18sp bold** (tv_title, font_muli_bold — framework sans-serif-bold stands in for Mulish Bold), maxLines 1, ellipsize end. Menu order unchanged — decoded evidence did not contradict the mined order.
- **Empty view (`list_layout_recyclerview.xml` rl_empty):** 15sp → **13sp**, colour mxskin__list_more (#DADDE4 dark / #96A2BA light), padding 12dp sides / 24dp top, top-centred (was full-centre).
- **Play-last FAB (`list_layout_recyclerview.xml` @id/play_last):** 56dp → **48×48dp**, margins end/bottom 16dp (already 16dp).
- **Status bar (`activity_media_list.xml` rl_status):** added — 24dp folder icon (marginLeft 18dp) + 12sp secondary text (padding L4/T12/R18/B12), pinned to the bottom of the list area over theme.card background. PARTIAL: MX's exact show-trigger and status text come from the un-decompiled fragment; ours shows it while a folder is open, with the folder's on-disk path as text. Background is ?listStatusBackground (theme attr, value not established) — theme.card used.
- **Pull-to-refresh (`activity_media_list.xml` SwipeRefresher host):** LANDED as a framework equivalent. The old handler was a crude invisible 120dp swipe with no affordance and no top check on release; replaced with a drag indicator that tracks the finger at 0.5× resistance while the list/grid is at its top ("↓ Pull down to refresh" → "↑ Release to refresh" → "Refreshing…"), triggers the rescan on release at full 48dp travel, and retracts otherwise. The touch listener never consumes events, so scrolling, clicks and long-press selection are untouched. Still gated by the Refresh-methods "swipe" setting, with the overflow Refresh button as the other affordance (MX places it there too via the same setting family).
- **Icons redrawn in code (never MX assets):** more dots at the catalogue's 18×18dp (ic_more_option / ic_grid_more), selected mark at 24×24dp (ic_file_selected), mini folder silhouette at the 12dp rl_info slot (ic_folder_internal, catalogue ~9.7×7.3dp) and the 24dp status slot. Folder icon itself stays the existing R.drawable.ic_folder redraw, now displayed at the decoded 62dp. PARTIAL: MX's folder/more/selected icons are raster-only in the catalogue (no pathData, no colour data), so silhouettes are redrawn to measured sizes/aspects but MX's exact raster colours are not in the decoded evidence — folder hues remain our redraw's.

### PARTIALs / honest unknowns

- **Adapter internals NOT established (spec-flagged, not guessed):** MX's thumbnail loader, view-type switching and grid span count were not decompiled. Our MediaStore MINI_KIND loader, BaseAdapter list/grid and AUTO_FIT 160dp column width stay underneath unchanged; only the item geometry MX's layouts establish was adopted. Grid container padding/spacing (16dp) is likewise not in the decoded item layouts and is retained from v0.2.
- **RecyclerView / SwipeRefreshLayout / CardView / ConstraintLayout / Flexbox:** framework-only per coordinator ruling — geometry replicated with LinearLayout/FrameLayout equivalents as noted per item.
- **MX subtitle tag on the thumb** (bottom, left of duration): left unpopulated — our model has no embedded-subtitle flag (FileOps' hasEmbeddedSubtitle is always false), so showing it would be fabrication.
- **Resume progress bar (2dp, bottom of thumb):** retained feature with no counterpart in MX's decoded row layouts; kept because the task requires resume badges to keep working.
- **Folder badge (red_badge count pill):** MX's layout defaults it invisible and its count semantics live in the un-decompiled adapter; not populated. The iv_new red dot (a decoded, visible-by-default element) is populated from the NEW-tag rule.
- **Mulish font:** MX's text appearances specify font_muli / font_muli_semibold / font_muli_bold; framework sans-serif / sans-serif-medium / sans-serif-bold stand in (font files are Foundation Team scope; MX's font files were not lifted).

### Feature-preservation check

Folders / All folders / Files views, folder navigation + back, search + search history, all 10 sort fields with MX direction labels, Fields dialog, list/grid toggle, multi-select + all 7 selection actions (Play selected, Play using…, Share, Lock in Private Folder, Hide, Rebuild thumbnails, Properties), per-item menu (Play, Play using…, Play next/later, Playlist, Favourite, Mark as…, Properties, Rename, Delete→recycle, Hide, Share, Create clip), overflow entries (Refresh, Layout, View Menu, Equalizer, View, Select, Playlists, Private Folder, Recycle Bin, History, Music, USB, Media Cleaner, Settings, Help, Quit-when-enabled), play-last FAB, first-run guide — all still wired; no handler was removed, only row/cell construction and chrome geometry changed.

### Compile evidence

Full-source kotlinc (the project's route: all `src/com/ghostblade/player/*.kt` against android-34 android.jar + the generated R class from `build/gen`), run twice:
- Run 1 (mid-flight tree): errors ONLY in PlayerActivity.kt (Player Team in flight — unresolved gridScrim/gridPanel/gridVisible); zero errors in MainActivity.kt / Library.kt / FileOps.kt.
- Run 2 (after Player Team landed): **0 errors in the whole tree**; MainActivity.kt / Library.kt / FileOps.kt class files produced. Only warnings in my files: two pre-existing `onBackPressed` deprecation warnings (lines 258, 691), unchanged from before this work.
No new permissions, no INTERNET, no AndroidX/RecyclerView introduced.

## Player Team — done notes

Scope: `src/com/ghostblade/player/PlayerActivity.kt` ONLY (7,185 → 7,469 lines). No other file touched. No new permissions, NO INTERNET, framework-only (no AndroidX). Binding truth: MASTER-SCENE-SPEC §S1–S5 + decoded layouts; decoded values supersede the v0.3 recording spec wherever they differed. Note on ENGINE-API.md: it did **not** exist when this work started (verified absent); the Engine Team's certification has since landed in it and in their notes above — §S4 handling below matches it exactly.

### §S1 Render-path swap — LANDED
- TextureView deleted. Video layer is now a framework `SurfaceView` created in code (MX's ActivityScreen pattern), first child of the root; overlays remain ordinary Views above it in the previous z-order. HW: `MediaPlayer.setSurface(holder.surface)`. SW: `SwVideoSession.attachSurface(holder.surface)` on surfaceCreated (repaint-on-attach restores the picture on recreate); detach + `mp.setSurface(null)` on surfaceDestroyed.
- Aspect/zoom/pan are now **surface sizing**: `applyVideoTransform()` computes the displayed size for fit/crop/stretch/100/custom (fit baseline × pinch scale) and sets the SurfaceView's centred layout rect + pan margins (pan clamped by the existing `ZoomMath.clampPan`). Aspect cycle, pinch zoom and pan keep their exact v0.3 user-visible behaviour through the new mechanism. `holder.setFixedSize` deliberately NOT used — HW decoder owns its buffer size and the SW native side sets its own window geometry in attachSurface; the layout rect is the single sizing authority (documented in code).
- Screenshot: the task's premise didn't match the code — `takeScreenshot()` never used `TextureView.getBitmap`; it re-decodes the frame via MediaMetadataRetriever, so it is surface-independent and works unchanged on HW/HW+/SW. No PixelCopy gate needed (documented in code).
- v0.2.1 fullscreen geometry survives: manifest untouched (cutout shortEdges intact), crop remains the default zoom mode.
- PARTIAL: mirror / vertical-flip now ride the SurfaceView's `scaleX/scaleY` (compositor applies it to the surface layer on modern Android; on very old devices it may flip only the empty view — state + rail dimming still track; noted in code).

### §S2 Cadence — LANDED
- One unified **100 ms** Handler loop while playing: progress + time labels + subtitle cue update in a single fan-out; position polls the engine clock on engine paths (`currentPos()` prefers `EngineBridge.position`). The separate 500 ms progress tick and 200 ms subtitle tick are **deleted**.
- Subtitle lookup now uses MX's formula `(pos − offset) × speedFactor`, sign-mapped to our offset convention as `(pos + offset) × speed` in `PlayerLogic.subLookupMs` (identical at speed 1; all existing JVM pins pass).
- **50 ms volume ramp loop** added (MX what=3 equivalent); "Fade on start" now ramps through it instead of the old 6×100 ms steps.

### §S5 Animations — LANDED
Controller fade **100 ms** in/out (alpha_show/hide class) · options menu **100 ms** in/out · sheets **300 ms** in AND out with accelerate-decelerate · decoder dialog fade **100 ms** · OSD bars (brightness/volume) + TapSeek self-hide **1,000 ms** · auto-hide default **3,500 → 2,000 ms** (`PSet.auto_hide_ms`; settings choices untouched).

### P1 Player scene — LANDED (decoded geometry)
- Transport rebuilt to `screen_subnavibar.xml`: top padder **12dip**, every button **50dp × 46dip** (`screen_subnavibar_height`), bottom padder **15dip**, order lock | backward(+10sp overlay text) | prev | **playpause centred** | next | forward(+text) | zb_left (aspect) | zb_right (PiP); lock marginLeft 8dp, PiP marginRight 8dp. The recording-derived 64dp/98dp-pitch arrangement is gone. Backward/forward are functional ±interval seeks (they had no v0.3 transport presence; double-tap seek also remains).
- Seek area per `screen.xml` progressPanel: padding L/R **8dip**; posText/durationText **12sp with paddingTop 10dp**; `MxSeekBar` bookmark marks are now **dots ON the bar, #99ffffff, 4dp diameter**, fed by the existing per-file bookmarks.
- Quick strip: host height **50dp** (decoded `@id/screen_shortcut_list`); items rebuilt to `screen_shortcut_*` geometry — 56dp cell, 40dp circle (8dp margins), 24dp glyph, 10sp label with 5dp top margin. PARTIAL (coordinator ruling, not fought): the strip host remains a framework `HorizontalScrollView` row, not a RecyclerView — no AndroidX in this app.

### P2 Right drawer / menu — LANDED with one PARTIAL
- Decoded truth check: **neither `view_player_menu.xml` nor `player_right_menu_layout.xml` contains an icon grid** — so per the task rule the v0.3 4×3 grid is **deleted**, not kept. The drawer is now the menu list (the decoded container's purpose), animated at the decoded 100 ms.
- The decoded **right rail** (`player_right_menu_layout.xml`) landed as its own surface: vertical, 16dp horizontal padding, **112dp bottom padding**, two **40dp** buttons with 8dp padding and a 16dp gap — PiP, then zoom/aspect — shown/hidden with the chrome.
- Every v0.3 grid item stays reachable: Playing Queue, Bookmark and Favourite rows were added to Play/Tools menus; Aspect Ratio, Display Settings, Cut (Create clip), Add To Playlist, Information (Properties), Share, Tutorial (Help) already lived in the menu.
- PARTIAL: menu panel width stays 300dp vs the decoded container's 200dp — at 200dp the full v0.3 row set (labels + values + check states) truncates badly; width kept for reachability/legibility, height/anchoring/animation follow the decoded drawer.

### P3 Subtitle panel + speed/sync bars — LANDED
- `subtitle_panel.xml` landed as a real bottom bar (**48dp** = `bottom_bar_min_height`): folder load button (16dp side padding) → `pickSubtitleFile()`, horizontally scrolling track chips (single-select, matching the one-active-track renderer; SW mode applies file/None sources without a MediaPlayer), close button. Menu Subtitle ▸ Panel now opens this bar (it previously aliased the sheet); the top-bar subtitle button still opens the sheet.
- `subtitle_speed_bar.xml` / `subtitle_sync_bar.xml` landed as floating bars in the decoded shared shape (header 14sp title + 42dp close; stepper row: minus, 15sp #CCCCCC editable value minWidth 56dp, 13sp unit, plus; 4dp margins). Subtitle menu Speed/Synchronization now open these bars instead of dialogs. The value field is a real EditText; typed values commit on stepper tap/close. The old speed dialog / sync step-dialog functions remain in the file (unused by the menu) — no feature lost.

### P4 Audio panel — LANDED (structure note)
The Audio Track sheet (radio list + Disable + SW-audio checkbox + Open/Stereo/Synchronization rows) is unchanged in content and remains hosted in the same right-side sheet system the decoded drawer family uses; it is also reachable from Audio menu ▸ Audio Track. No item added or dropped.

### P5 Decoder dialog — LANDED with note
Verified against `dialog_options_menu.xml` and adopted its decoded footer: panel paddingTop **12dp**, Cancel (16sp, tertiary) / Done (16sp, accent, bold) footer with 10dp padding + 4dp top margin + 8dp bottom placeholder. Note: the decoded layout is a full options-list shell (RecyclerView + Cancel/Done) with **no row geometry**, so the recording's 45dp radio rows and 568dp max width stay (framework rows per the no-AndroidX ruling); SW honesty gating unchanged.

### P6 OSDs — LANDED
- TapSeek: self-hide **1,000 ms** (was 650 ms).
- Lock: unlock affordance sized to `lock.xml` (**50dp × 46dip**).
- Zoom bar: landed in the decoded floating-bar shape (see P3) — stepper-driven zoom with an editable % value, wired to the same zoom state as pinch/aspect.
- Vertical bars: new pure `BrightnessMap` — gesture fraction → window brightness through the decoded **1.8 factor**, **min 0.05**, max on the framework **255** scale, bar label `"%d%%"` of the raw fraction; the drag starts from the inverse-mapped current brightness so there is no first-move jump. Volume bar keeps its fraction + % label and the 1,000 ms self-hide.

### §S4 Sync — LANDED per certification
ENGINE-API.md now certifies SW sync is engine-internal. Accordingly: the 250 ms `AvSyncPolicy` ticker is **never posted in SW mode** (and `syncEngineClock()` already returned early there). It is **kept for HW+ only**, where it slaves MediaPlayer video to the engine audio clock — exactly the split the Engine Team certified. `AvSyncPolicy` itself stays (JVM-pinned, HW+ consumer live).

### Feature-preservation check
Gestures (seek/brightness/volume/sub-move/sub-sync/two-finger speed/long-press FF), lock + kids lock modes, PiP, background play, A–B repeat, sleep timer, clip entry (Tools ▸ Create clip + rail), equalizer intent, subtitle styling/sync/speed, resume, decoder dialog SW honesty, bookmarks, playlist drawer, external audio — all still wired; the only surface removed is the More grid, with every item re-homed (see P2).

### Compile evidence
- Full-source kotlinc via the project's route (all `src/com/ghostblade/player/*.kt` vs android-34 android.jar + aapt2-generated R): **EXIT 0** — warnings only, all pre-existing deprecations; zero errors in PlayerActivity.kt or any other team's file.
- JVM suites (PlayerLogicTest + SubtitleTest + SubtitleFormatsTest, compiled with ClipEditor sources added): **PlayerLogicTest 162 checks, 0 failures · SubtitleTest 95/95 · SubtitleFormatsTest 103/103**.
- Known pre-existing breakage, NOT mine: `tests/run-player-tests.sh`'s file list omits `ClipEditor.kt`/`ClipEditorActivity.kt`, so it fails with "unresolved reference 'ClipEditorActivity'" — verified identical against the pristine v0.3 PlayerActivity.kt backup (same error, 2 sites). The runner's list needs the two ClipEditor files added (Stage Team owns those files; flagging, not editing).

## DELIVERY — 2026-11-22 18:44 +06
- **Overall programme: 100%** — v0.4 build complete AND delivered to Sir. All teams done.
- Jervis personal verification (2026-11-22 ~18:40–18:44 +06): all suites re-run personally via tests/run-tests.sh + tests/run-player-tests.sh — TestMain 208 · PlayerLogicTest 162 · SubtitleTest 95 · SubtitleFormatsTest 103 · ClipEditorTest 51 = **619 checks, 0 failures**.
- APK facts re-verified personally: GhostBladePlayer-v0.4.apk 6,382,363 bytes, SHA-256 28b4e93c3956b79a3ca11e725340dd1aefdbf0a9c2b2f93ae53446a36b7850a0, versionCode 5 / 0.4, signer cert e38da55a054e6a24045121db1a7a69957c13db68911ec8abb94f2a15d2b193eb, exactly 7 permissions (NO INTERNET, no location), 11 native libs incl. the libass set, windowLayoutInDisplayCutoutMode=1 (shortEdges) in the binary manifest, supportsPictureInPicture=true.
- Delivered to Sir in chat 2026-11-22 ~18:44 +06 with the side-by-side report (function / UI / smoothness) he asked for. Board cron stop condition met: board to DONE 100%, cron live-board-v4-team-status to remove itself.
