# GhostBlade Player v0.5 — Independent Audit (Shadow Assist Team)

**Scope:** read-only audit of the shipped v0.5 source + APK against the v0.5 measurement
spec (`v5-alignment/parts/player.md`, `v5-alignment/parts/library-settings.md`) and the
11 claimed change groups. No app source was edited. Every finding cites file + line.

**Method:** line-level source inspection of PlayerActivity.kt (7,490 lines),
MainActivity.kt (2,208), SettingsActivity.kt, Surfaces.kt, res/drawable/ic_folder.xml;
independent aapt2 spot-check of the shipped APK.

---

## P1 FINDINGS

### P1-1 — Claimed fix #5 landed on the wrong panel: the player MENU is still 300dp × full height
Spec §1.7 maps MX's `view_player_menu.xml` container (**200×360dp**, bottom-right anchored)
to our **menuPanel**, measured at 300dp × MATCH with fix value 200×360dp.

In the shipped source, menuPanel is unchanged:

- `PlayerActivity.kt:2979-2982` — `root.addView(menuPanel, FrameLayout.LayoutParams(dp(300), MATCH, Gravity.RIGHT))`
- `openMenu()` (`PlayerActivity.kt:4683-4696`) only animates alpha; it never resizes the panel.

The 200×360dp size was instead applied to **drawerPanel** — the *Playlist* drawer
(`PlayerActivity.kt:3001`, `FrameLayout.LayoutParams(dp(200), dp(360), Gravity.RIGHT or Gravity.CENTER_VERTICAL)`),
a different surface that the spec did not measure for this fix. drawerPanel is also
centre-vertical anchored, not bottom-right as MX's container is.

**Result:** the panel Sir opens from the player's ⋮ button is still 300dp wide and
full-height — exactly the geometry spec P1-6 flagged. The claim "drawer panel 200×360
(was 300×full)" is true only of the playlist drawer; for the spec's target surface it
does not hold.

### P1-2 — Centre pitch fix is frozen at entry orientation; portrait entry keeps 50dp pitch in landscape
The pitch mechanism itself is as claimed (`PlayerActivity.kt:2874-2877`):
`tGap = (screenWidthDp − 466) / 2`, clamped 0..49, applied as playBtn left/right margins;
at the clamp the pitch is 50 + 49 = **99dp** = the spec's ≈99dp target.

But the gap is computed **once**, inside `buildBottomBar()`, which is called exactly once
from `buildUi()` during `onCreate` (`PlayerActivity.kt:2283`) — *before* `applyOrientation()`
runs (`onCreate` sequence at `PlayerActivity.kt:2030-2033`: `buildUi()` → `applyFullscreen()`
→ `applyOrientation()`; `setRequestedOrientation` takes effect asynchronously, so
`displayMetrics` during the build still reflect the entry orientation).
PlayerActivity declares `orientation|screenSize|…` in `android:configChanges`
(AndroidManifest.xml, PlayerActivity entry) and has **no** `onConfigurationChanged`
override (grep count 0), and `tGap` is never recomputed (only two references in the file:
definition + use).

Consequence in the dominant flow — Sir taps a video in the portrait library (his own
screenshots show a portrait library), the player builds at 393dp width →
`tGap = (393 − 466) / 2 = −36 → clamped 0` → pitch **50dp**, and rotating to landscape
never widens it. The spec's P1-3 fix therefore takes effect only when the player happens
to be launched while the phone is already in landscape.

---

## P2 FINDINGS

### P2-1 — Folder badge centre is ~3dp off the glyph's true top-right corner; comment math doesn't match the vector
Badge: `MainActivity.kt:1763-1783` — 20dp tall, 11sp, `minimumWidth = dp(20)`,
margins (left 60dp, top 2dp) → badge centre for a single-digit count = **(70, 12)** in the
76dp slot.

Glyph corner from the shipped vector (`res/drawable/ic_folder.xml:9-12`): path bounds
x 1.9–46.1, y 6.2–39.6 of a 48-unit viewport → in a 76dp view the silhouette spans
x 3.0–73.0dp, y 9.8–62.7dp. True top-right corner = **(73.0, 9.8)**.

Badge centre is therefore **3.0dp left and 2.2dp below** the corner it is meant to centre
on. The in-code comment (`MainActivity.kt:1776-1777`) claims the glyph corner is at
(70, 11.6) — that figure treats the 92% width as starting at x=0 and assumes the glyph is
vertically centred in the viewport; neither holds for the shipped path. The badge still
straddles the corner region visually, but "centred on the glyph's top-right corner" is
measurably inexact. Additionally, because the left edge is pinned at 60dp and width grows
with digit count, the centre drifts right for 2-digit counts (≈73dp — ironically nearer
the true corner) — centring is not consistent across counts.

### P2-2 — Residual spec items outside the v0.5 claim list (noted for honesty; not claim failures)
These spec fixes were **not** in the 11 claimed change groups and remain open:
settings row minHeight still 64dp (spec §1.7 fix: 48dp; `SettingsActivity.kt` rowShell);
selection-bar actions still 13sp text labels (spec §1.4/§1.6: 48×48dp icon buttons);
no "All" button in the app bar (spec §1.4 device composition);
floating-bar steppers still 42×42dp (spec P2-9: 52×52dp);
subtitle/audio sheet rows still 45dp (spec §1.5: ≈48dp);
decoder label still 15.5sp (spec §1.1 suggests the 14sp class; MX value not pinned);
player top-bar slots still 48dp in the 56dp bar (spec P3-9);
music rows / music widgets unchanged (spec §1.8/§1.10).

---

## P3 / NOTES

- **GlyphDrawable LIST stroke mutation** — `MainActivity.kt:275`:
  `canvas.drawLine(…, stroke.apply { strokeWidth = 1.9f * u })` mutates the shared
  `stroke` Paint mid-draw. Harmless in practice: `draw()` resets `strokeWidth = 1.6f * u`
  on entry (`MainActivity.kt:243`), every kind sets its own width before stroking, and
  each GlyphDrawable instance only ever draws one kind. Code smell, not a visual defect.
- **VerticalBar.labelPaint is dead** — declared `PlayerActivity.kt:3499`, never read;
  `onDraw` uses only `textPaint` (whose initial 30f size at `PlayerActivity.kt:3496` is
  overwritten with `13f * sd` before first use, `PlayerActivity.kt:3541`). Warning-level
  only; the `label` constructor parameter itself is live (selects BRIGHTNESS vs VOLUME).
- **barTextButton is dead code** — defined `MainActivity.kt:489`, zero call sites
  (the Unicode bar buttons it served are gone). Warning-level.
- **MxIcon.STICKY (kind 33)** — still defined and drawn (`PlayerActivity.kt:1226, 1510`)
  but never instantiated anywhere; expected leftover of the P1-7 resolution (below).
- **BACK ink rounding** — shipped glyph ink is 15.7 × **15.4**u (head (11,4.3)–(4,12)–
  (11,19.7) + shaft to x19.7; `PlayerActivity.kt:1396-1401`) vs the claimed ~15.7×15.3.
  0.1u — negligible.
- **GlyphDrawable kind numbering** skips 7 (NOTE = 8). Harmless.

---

## CLAIM-BY-CLAIM VERIFICATION

### Player (PlayerActivity.kt)
1. **Glyph redraws — VERIFIED**, all in `MxIcon.draw()`:
   PLAY triangle (4,2)–(20.5,12)–(4,22) = ink 16.5×20u (L1274-1278);
   PAUSE bars (3.6,2)–(9.9,22) & (14,2)–(20.3,22) (L1280-1283);
   NEXT ink x3–21 × y3–21 = 18×18u (L1284-1289), PREV mirrored (L1291-1295);
   FORWARD/BACKWARD now double-triangles (L1296-1312);
   LOCK body (2.5,10)–(21.5,22), shackle arc rect (5.5,1)–(18.5,11.5) (L1314-1333);
   ROTATION = phone rect rotated −20° + filled lock badge (L1334-1344);
   AUDIO_TRACK ink x4–17 × y3–21 = 13×18u (L1345-1352);
   SUBTITLE card (2,2)–(22,22) (L1353-1357);
   MORE dots at cy 6/12/18, r 2u (L1358-1361);
   BACK head+shaft ink 15.7×15.4u (L1396-1401; see P3 rounding note);
   PIP outer (1.5,3)–(22.5,21) + solid inner (L1406-1409);
   PLAYLIST = two lines + single right triangle (L1447-1454);
   ASPECT_FIT/STRETCH/CROP/100 all share outline (1,3)–(23,21) (L1525-1560);
   FOLDER now a filled silhouette path (2,4.5)–(22,20) (L1613-1618).
2. **Play padding / pitch — VERIFIED with P1-2 caveat.** playBtn padding (9,7) →
   32×32dp glyph box in the 50×46dp button (L2861-2870); pitch margins per the formula
   at L2874-2877 (99dp at clamp).
3. **Title 14sp — VERIFIED** (L2519, was 17.5sp). **Aspect OSD 52sp — VERIFIED**
   (L2290, was 58sp).
4. **Brightness/volume OSD — VERIFIED.** Containers 42×156dp, 16dp margins
   (L2299-2310). `VerticalBar.onDraw` rebuilt (L3513-3545): 20dp icon, 4×92dp bar
   (barTop 30dp), % text at `13f * scaledDensity`, all sizes density-scaled.
   Dead `labelPaint` — see P3.
5. **Drawer panel 200×360 — NOT VERIFIED for the spec's panel.** See P1-1.
6. **Strip — VERIFIED.** Speed text 12sp (L2652-2662, was 15sp); audioEffect red dot
   10dp (L2673-2685, was 8dp); chevron disc 30dp (L2710-2724) with 14dp glyph box
   (30dp view − 8dp margins ×2), was 24dp disc.

### Spec P1-7 adjudication — I AGREE with Jervis: the spec misread the code
- `shortcutFor("sticky")` returns **MxIcon.HEADPHONES** with the background-play action
  (`startBackgroundPlay()` + `moveTaskToBack(true)`) — `PlayerActivity.kt:5858-5865`.
  The strip renders the glyph from `shortcutFor`, never the MxIcon.STICKY pin kind.
- Its strip label resolves via `Settings.kt:254` — `"sticky" to "Background Play"`.
- `DEFAULT_RAIL` (`PlayerActivity.kt:729-731`) filtered by `stripPinnedIds`
  {equalizer, playback_speed, screenshot, sticky, screen_rotation} (L2605-2606) in rail
  order (`rebuildStrip`, L2688) yields the collapsed order: equalizer · playback_speed ·
  screenshot · **headphones/background-play** · screen_rotation — MX's device order
  (spec §2.7). Position 4 is correct as shipped.

### Library (MainActivity.kt / SettingsActivity.kt / Surfaces.kt / ic_folder.xml)
7. **App bar — VERIFIED.** `barIconButton` (L353-361): fixed 48×48dp, padding 12dp →
   drawn 24dp glyphs via the new `GlyphDrawable` (L219-351). Bar buttons: back (L511),
   play-last, search, **view-toggle added** (L527-533, glyph swaps in `updateChrome`
   L937-942), overflow (dotsDrawable in a 48×48 slot, L531-533). Root title **"Folders"**
   (`updateChrome`, L935). Net title inset at root = bar paddingStart 8dp + title
   paddingStart 8dp = 16dp, matching the spec fix value.
8. **Quick-access row + bottom nav — VERIFIED.** `buildQuickRow` (L373-421): Music /
   My Playlists / Cleaner / Privacy; 44dp circles, glyphs at 24dp (ImageView padding
   10dp in a 44dp frame, L391-397), labels 11sp (L399-406). MX's File Transfer and
   Status Saver are absent — both are cut-feature class (network / external app),
   consistent with Sir's standing cuts. `buildBottomNav` (L427-466): Local / Music /
   Me, 24dp glyphs + 11sp labels; Transfer absent (cut). Visibility driven from
   `updateChrome` (L943-948): quickRow only at the folders root (not searching /
   selecting / inside a folder / non-folders view mode); bottomNav hidden only in
   search/selection. Call sites cover every transition: buildUi (L733),
   refreshDisplay (L922 — folder enter/exit, layout and view-mode changes),
   enterSearch/exitSearch (L974/985), enterSelection/toggleSelection/exitSelection
   (L1070/1077/1084), overflow Select (L1444).
9. **Folder rows — VERIFIED with P2-1 caveat.** Icon slot 76dp (L1746-1750); numbered
   red badge 20dp tall / 11sp replacing the 10dp dot (L1763-1783); count wording
   "N videos" / "1 video" in list (L1806) and grid (L1857); third info line removed —
   the row builds title + count row only (L1796-1832).
10. **ic_folder.xml — VERIFIED.** Grey two-tone silhouette (#9E9E9E body, #CCCCCC front
    panel); path bounds x 1.9–46.1 = 92.1% of viewport width, y 6.2–39.6 = 69.6% of
    height — the claimed 92%×70% (file lines 9-12).
11. **Thumb/FAB/mini/settings — VERIFIED.** Thumb type icon = drawn GlyphDrawable PLAY
    at 32dp list / 40dp grid (L1927-1936, was "▶" text); favourite = drawn filled HEART
    path in 16dp list / 24dp grid slots (L1960-1972, was "♥" text); FAB = drawn PLAY
    glyph centred in the 48dp FAB (L668-688, glyph at L676); `folderMiniDrawable`
    redrawn to x 0.10–0.90 × y 0.20–0.80 of its box (aspect 1.33:1 ≈ MX's 1.32:1) in
    MX greys (L186-211) — exactly the spec §2.1 fix values; Settings chrome title bold
    (`SettingsActivity.kt:335`, `Typeface.create("sans-serif", Typeface.BOLD)`);
    Surfaces chrome title bold (`Surfaces.kt:235`).

---

## BREAKAGE / CONSISTENCY CHECKS
- No referenced-but-missing symbols: the shipped APK exists and its hash (below) matches
  the claimed build of these sources, so the source set compiles and links as shipped.
  Remaining unused items are warnings only (P3): `barTextButton`, `VerticalBar.labelPaint`,
  MxIcon kind 33.
- GlyphDrawable LIST stroke mutation: present but provably inert (see P3).
- quickRow/bottomNav visibility: logic and call sites consistent (claim 8 above).
- Badge layout math: see P2-1.

## APK SPOT-CHECK (independent, aapt2 build-tools 35.0.0)
- Size **6,386,459 bytes** — matches the claimed figure exactly.
- SHA-256 **fc342660981db7cfad64c18268803c9bd0dda05b3e29aa3b94d0592ce72b01d2** —
  matches the claimed hash exactly.
- `versionCode='6'`, `versionName='0.5'`, package `com.ghostblade.player` — confirmed.
- Permissions: exactly **7** (READ_MEDIA_VIDEO, READ_MEDIA_AUDIO,
  READ_EXTERNAL_STORAGE ≤32, MODIFY_AUDIO_SETTINGS, FOREGROUND_SERVICE,
  FOREGROUND_SERVICE_MEDIA_PLAYBACK, POST_NOTIFICATIONS) — **no INTERNET** — confirmed.
- **11 native libs** under lib/arm64-v8a (incl. libass/libgbass/libgbffmpeg) — confirmed.
- Binary manifest: `windowLayoutInDisplayCutoutMode = 1` (shortEdges) on PlayerActivity
  — confirmed via aapt2 xmltree.
- The APK on disk is therefore exactly the build Jervis described, of this source state.

## NOT VERIFIABLE STATICALLY
- On-device rendering: actual optical alignment, and whether P1-2's portrait-entry flow
  is Sir's exact launch pattern on the POCO F5 (analysis says it is the default flow;
  only a device test settles it).
- The 619-check JVM suite results are Jervis's own run; not re-run by this audit
  (out of the assigned spot-check scope).
- MX raster dot proportions behind `dotsDrawable` (unchanged by v0.5; spec §5 already
  records this as unmeasurable from catalogue data).
- Note on spec sources: `parts/library-settings.md` cross-references §3/§4/§5 sections
  (device evidence, priorities, limits) that are not present in the file on disk — it
  ends at §2.2. The fix values used above come from its §1/§2 tables, which are complete.

---

## VERDICT: PASS-WITH-NOTES

10 of 11 claimed change groups verify exactly, the spec's P1-7 is confirmed a misread
(the strip's 4th item already was headphones/background-play), and the shipped APK is
byte-identical to the claimed build (hash, size, versionCode 6, 7 permissions, no
INTERNET, shortEdges, 11 libs all independently confirmed). Two P1s keep this from a
clean PASS: the 200×360dp menu fix landed on the playlist drawer while the player menu
panel — the surface the spec measured — remains 300dp × full-height (P1-1), and the
centre-pitch fix is computed once from the entry orientation, so the normal
portrait-entry flow keeps the old 50dp pitch in landscape (P1-2). One P2 (badge
centring ~3dp off the glyph corner) and minor dead-code notes follow.
