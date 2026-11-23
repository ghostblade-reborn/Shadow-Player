# GhostBlade Player v0.5 — PLAYER alignment & icon fidelity (Worker 1, Shadow Measure Team)

**Scope:** player surfaces only. Static measurement only — no source was edited.
**MX source:** decoded MX Player 2.12.4 (`~/workspace/projects/mxplayer-analysis/decoded/`), `mx-icons/ICON-CATALOGUE.md`, `MASTER-SCENE-SPEC.md`, plus Sir's real-device screenshots (see §2 — device evidence outranks inference).
**Our source:** `~/workspace/projects/ghostblade-player/src/com/ghostblade/player/PlayerActivity.kt` (7,469 lines; all player chrome is programmatic).

> **Correction to the brief:** there is **no `Icons.kt`**. All player icons live in `class MxIcon` inside `PlayerActivity.kt` (declared L1191, kinds 1–55 L1195–1249, `draw()` from L1259, 24-unit grid, base stroke 1.6u at L1268). Every "our icon" citation below is to that class.
> **Correction to the brief's MX path:** `RESOURCE-TRUTH.md` is at `decoded/RESOURCE-TRUTH.md`, not at the analysis root.
> **MX icon medium (headline fact):** MX's player transport/shortcut icons are **density raster WebP**, not vectors — `ic_button_play`, `ic_button_pause`, `ic_button_next`, `ic_button_prev`, `ic_button_forward`, `ic_button_backward`, `ic_button_pip`, the zoom/lock frames etc. are all flagged `RASTER-ONLY (no vector variant)` in `ICON-CATALOGUE.md`. There is no MX `pathData` for them to copy; geometry below is measured from the xxhdpi rasters' alpha (ink) bounding boxes, which is the only MX geometry that exists. The level-lists (`ic_button_play_pause`, `ic_button_zoom`, `ic_button_locks`, `ic_button_decoder` in `res/drawable/`) only switch between those rasters.

**How MX renders a raster icon (applies to every transport row):** MX's buttons carry **no explicit padding** (`screen_subnavibar.xml`), and `ImageButton`'s default `scaleType` is `CENTER` — the raster is drawn at its **intrinsic dp size, centred** in the button. So MX's effective glyph size = the raster's intrinsic size (e.g. play = 32dp), *not* the button size. Our `MxIcon` instead always fills the padding box of its button (24dp in transport, see §1.3), which is the single root cause of most P1s below.

---

## 1. ALIGNMENT TABLE — player chrome

Citations: MX = decoded file + attribute/dimen; Ours = `PlayerActivity.kt` line. `dp()` = density-scaled in code.

### 1.1 Top bar (`screen_toolbar.xml` + `res/menu/screen.xml` / `screen_new.xml`)

| Element | MX value | Our value | Delta | Fix value |
|---|---|---|---|---|
| Bar height | `?actionBarSize` = **56dp** (`screen_toolbar.xml` `layout_height="?actionBarSize"`; `actionBarSize=@dimen/mtrl_toolbar_default_height`=56.0dip, RESOURCE-TRUTH styles) | topBar row `MATCH × dp(56)` (buildTopBar, ~L2555) | 0 | 56dp ✓ |
| Back button slot | Toolbar navigation button, 56dp-tall bar; icon `ic_back` raster **24×24dp** intrinsic, ink 15.7×15.3dp, centre (12.17, 12.00)dp (xxhdpi alpha bbox 13,13–60,59 /3) | `chromeButton`: slot **48×48dp**, padding 12dp → glyph box **24×24dp** (L2562–2568) | slot −8dp height vs bar; glyph box = MX intrinsic ✓ | Slot 56×56dp (or MATCH height), glyph stays 24dp |
| Title text size | **14sp** (`@style/ToolbarTitleText`: `textSize 14.0sp`, font Muli semibold, `res/values/styles.xml`) | **17.5sp**, `Typeface.DEFAULT_BOLD`, padding L2/R8dp (L2518–2523) | **+3.5sp (+25%)** | **14sp** |
| Title lines/ellipsize | single-line toolbar title (AppCompat default) | `maxLines=1`, END ellipsize (L2520) | 0 | ✓ |
| Audio-track button | menu item `@id/audio`, icon `ic_audiotrack_white_24dp` **24×24dp**, ink 13.0×18.0dp, centre (12.50, 12.00)dp — 0.5dp right of centre (xxhdpi bbox 18,9–57,63) | `chromeButton(MxIcon.AUDIO_TRACK)`: 48dp slot, glyph box 24dp (L2543) | glyph box ✓; slot −8dp | 56dp slot |
| Subtitle button | menu item `@id/caption`, icon `ic_chat_white_24dp` **24×24dp**, ink 20.0×20.0dp, centre exactly (12,12)dp (bbox 6,6–66,66) | `chromeButton(MxIcon.SUBTITLE)`: 48dp slot, glyph box 24dp (L2544) | glyph box ✓; slot −8dp | 56dp slot |
| Decoder label | menu item `@id/decoder`, **text-only, no icon** (`screen.xml` menu: no `android:icon`); text = HW/HW+/SW | `decoderBtn` TextView, plain text, **15.5sp bold**, padding 8/6dp (L2527–2530) | text size: MX toolbar menu text appearance not pinned in decoded values (see §5); ours is +1.5sp over MX's 14sp title class | 14sp class |
| Battery / clock | menu item `@id/clockBattery` exists in MX (`BatteryClockActionView`, `showAsAction=always|withText`) **but is NOT visible in Sir's device configuration** — see §2.5 | **Not drawn** in player chrome (removed v0.3; comment L4556–4560; `PSet.batteryClock` key L594 retained for Settings only) | **MATCH with device evidence** | none |
| Overflow (⋮) button | `@id/more`, icon `ic_more_white_24dp` **24×24dp**, ink only 4.0 wide × 16.0 tall dp, centre (12,12)dp (bbox 30,12–42,60) | `chromeButton(MxIcon.MORE)`: 48dp slot, glyph box 24dp (L2545) | glyph box ✓ | 56dp slot |
| Bar background | MX toolbar floats over video with the controller gradient (PlaybackController `tag="styleable.gradient.bottom_to_top"`, `screen.xml`) | topChrome: TOP_BOTTOM gradient argb(128,0,0,0)→transparent (buildTopBar) | not numerically comparable (gradient stops are MX code-side) | — |

### 1.2 Quick-actions strip (`screen.xml` `@id/screen_shortcut_list` + `screen_shortcut_image/text/mixed.xml`)

| Element | MX value | Our value | Delta | Fix value |
|---|---|---|---|---|
| Strip height | **50dp** (`screen.xml`: RecyclerView `layout_height="@dimen/dp50"`) | collapsed & expanded rows both `dp(50)` (buildStrip, ~L2588–2600) | 0 | ✓ |
| Cell width / pitch | **56dp** cell (`screen_shortcut_image.xml` root `layout_width="@dimen/dp56"`); pitch = **56dp** | collapsed: 40dp circle + L/R margins 8+8 = **56dp pitch** (rebuildStrip, ~L2689–2695); expanded cell `dp(56)` wide | 0 | ✓ |
| Circle size | **40dp** (FrameLayout `dp40×dp40`, margins L/R/T 8dp) — **device screenshot measures ≈44.5dp, see §2.1 (conflict flagged)** | disc **40×40dp** (stripCircle, ~L2627–2666) | 0 vs decoded; **−4.5dp vs device** | 40dp per decoded; resolve conflict in §2.1 |
| Glyph inside circle | **24×24dp**, centred (`screen_shortcut_img` `dp24`, `layout_gravity=center`) | ImageView 40dp with 8dp margins all round → glyph **24×24dp** centred (~L2676–2685) | 0 | ✓ |
| Text-type shortcut | **12sp** Muli semibold centred in the 40dp circle (`screen_shortcut_text` `sp12`) | speed item text **15sp bold** in 40dp circle (~L2651) | **+3sp** | **12sp** |
| Label under circle (expanded) | **10sp** (`sp10`), marginTop **5dp** (`dp5`), minLines 2, shadow 1/1/2 | **10sp**, paddingTop **5dp**, maxLines 2 (~L2727–2730) | 0 | ✓ |
| Red-dot badge | `dp10_un_sw` = **10dp** (image/mixed variants) / `dp8` = 8dp (text variant), top-right | **8×8dp**, offset top/right 2dp, colour #F43F5E (~L2690–2700) | −2dp vs image-variant | 10dp |
| Chevron (expand) circle | No layout value — the strip is a RecyclerView; the ">" affordance is code-side in MX. **Device: ≈30dp circle, ≈0.67× main circle (§2.7)** | **24dp** disc, glyph box 24dp with 5dp margins → glyph **14dp**; container marginLeft 16dp (~L2703–2715) | ≈−6dp vs device | ≈30dp disc |
| Collapsed item order | Code-side in MX (adapter); **device order: equalizer · "1X" · screenshot · headphones · rotate-phone · › (§2.7)** | pinned subset of DEFAULT_RAIL (`equalizer,playback_speed,screenshot,sticky,screen_rotation`, L731; pinned set L2604–2605) rendered in rail order → **equalizer · speed · screenshot · STICKY · rotation** | **4th item differs: ours sticky (pin), MX device headphones (background play)** | 4th pinned = background-play/headphones |

### 1.3 Transport row (`screen_subnavibar.xml`; height `screen_subnavibar_height`)

| Element | MX value | Our value | Delta | Fix value |
|---|---|---|---|---|
| Bar height | **46dp** (`screen_subnavibar_height` = 46.0dip, dimens.xml) + top padder **12dp** + bottom padder **15dp** (both literal in layout) | buttons `dp(46)`, top spacer `dp(12)`, bottom spacer `dp(15)` (buildBottomBar, ~L2845–2895) | 0 | ✓ |
| Every button size | **50dp wide × 46dp high** (`dp50` × `screen_subnavibar_height`) | **50×46dp** all buttons (L2845, 2852, 2859, 2866, 2867, 2874, 2886, 2890) | 0 | ✓ |
| Lock margin | marginLeft **8dp** (`dp8`) | leftMargin **8dp** (L2845) | 0 | ✓ |
| PiP (zb_right) margin | marginRight **8dp** (`dp8`) | rightMargin **8dp** (L2890) | 0 | ✓ |
| Backward/forward padding | ZoomButton padding **8dp** (`dp8`) → raster box 34×30dp, raster centred at intrinsic 24dp | subnavButton padding **13/11dp** → glyph box **24×24dp** (L2828) | padding differs; effective glyph: MX 24dp intrinsic ✓ = ours 24dp box ✓ | keep 24dp glyph |
| Play/pause, prev, next, lock, zoom padding | **none specified** → ImageButton CENTER, raster at intrinsic size | padding **13/11dp** → glyph box **24×24dp** for every button (L2828, 2863, 2883) | **play/pause glyph box −8dp (MX play intrinsic 32dp, pause 33×32dp)**; others = 24dp ✓ | play/pause glyph box **32dp** (padding ≈9/7dp) |
| Centre-group pitch (prev↔play↔next centres) | Decoded layout: buttons contiguous (`toLeftOf/toRightOf` chain, playpause `centerHorizontal`) → **50dp**. **Device screenshot: ≈98–100dp (§2.8) — device outranks; MX evidently spaces this group in code at runtime** | **50dp** (contiguous 50dp buttons in centre LinearLayout) | **≈−49dp vs device** | **≈99dp pitch** (50dp buttons + ≈49dp gaps) |
| ±seek overlay text | **10sp** (`sp10`) white Muli semibold, centred on the 50×46 button (`backward_text`/`forward_text`) | **10sp bold**, centred (intervalLabel, ~L2834) | 0 | ✓ |
| Press state | `styleable.transparent_button` tag; pressed-state drawable is code/theme-side (not in layout) | blue-rect press bg, ACCENT fill, corner 3dp (blueRectPressBg ~L2746) | not measurable from MX layout | — |

### 1.4 Seek bar area (`screen.xml` PlaybackController / progressPanel)

| Element | MX value | Our value | Delta | Fix value |
|---|---|---|---|---|
| Row padding | paddingLeft/Right **8dp** (literal `8.0dip`) | seekRow padding **8dp** L/R (~L2790) | 0 | ✓ |
| Time label size | **12sp** (`@dimen/dp12` used as textSize), font Muli | **12sp** (timeLabel L3444) | 0 | ✓ |
| Time label paddingTop | **10dp** (`@dimen/dp10` on posText; durationText same) | **10dp** top, 2dp L/R (L3446) | 0 | ✓ |
| Label placement | posText LEFT of bar, durationText RIGHT of bar, **same row** (LinearLayout, bar weight 1) — confirmed by device §2.6 | curTime LEFT, seekView weight 1, durTime RIGHT, same row (~L2790–2810) | 0 | ✓ |
| Bar↔label gap | none specified (bar directly adjacent, weight-filled) | seekView margins L/R **10dp** | +10dp gap each side vs MX adjacency | 0dp (or keep — flag, low visibility) |
| Seek view height | BookmarkSeekBar `layout_height 0px`, `maxHeight 50dip` in a wrap-content row → row height driven by labels (≈12sp + 10dp pad) | seekView height **36dp** (LayoutParams `0 × dp(36)`, weight 1) | row taller than MX's label-driven row | label-driven height (≈28dp) |
| Track thickness | BookmarkSeekBar is custom-drawn (`progressDrawable=@null`); the related `bookmark_seek_bar_progress.xml` drawable defines track height **4dp** (`dp4`), radius 2dp, bg `#4cffffff`, progress `#ff3c8cf0` | **2dp** hairline (MxSeekBar onDraw: `trackH = 2f * density`, ~L3555) | **−2dp vs decoded drawable** (device apparent ≈2dp, §2.6) | 4dp per drawable; device says thinner — resolve at build |
| Bookmark dots | `dot_color #99ffffff`, `dot_size dp4` → **Ø4dp dots on the track** (screen.xml app: attrs) | **Ø4dp** dots (`2f * density` radius), colour argb(0x99,…) (MxSeekBar ~L3546–3570) | 0 | ✓ MATCH |
| Thumb | No thumb drawable in layout (custom BookmarkSeekBar draws its own; size not in resources — **device: Ø≈17dp, §2.3**) | **Ø17dp** (`8.5f * density` radius, MxSeekBar onDraw) | **0 vs device** | ✓ MATCH (17dp) |
| Buffered layer | not in decoded layout/drawable set found | buffered rect argb(0x73,…) drawn (MxSeekBar) | unmeasurable for MX | — |

### 1.5 Subtitle / audio sheets & panels

| Element | MX value | Our value | Delta | Fix value |
|---|---|---|---|---|
| Audio/subtitle list item (landscape panel) | `item_audio_subtitle_landscape.xml`: width **180dp**, text **16sp** Muli semibold, padding **12dp** all sides → row height ≈ 16sp text + 24dp ≈ **48dp** | sheet rows: height **45dp**, text **16sp**, radio/check box 24dp, label paddingLeft 14dp (sheetRadioRow/sheetCheckRow L3080–3124) | row −3dp; text ✓ | 48dp rows, 12dp padding model |
| Subtitle panel bar (bottom) | `subtitle_panel.xml`: bar height `bottom_bar_min_height` = **48** (dimens, unit written `48.0sp` in dimens.xml — treated as 48dp); load/close buttons padding L/R **16dp**, icons 24dp (`ic_folder_white_24dp`, holo close) | subPanelBar height **48dp**; load/close padding **16/12dp** (buildSubPanelBar L3199, 3216) | 0 (height/padding-h ✓) | ✓ |
| Subtitle item (old list) | `subtitle_item.xml`: checkbox text **14sp**, paddingLeft 6dp | sheet check rows 16sp (above) | +2sp vs this legacy item; MX's current panel item is the 16sp one above | 16sp ✓ (use landscape item as reference) |
| Subtitle item (new, with AI row) | `subtitle_item_new.xml`: checkbox **13sp**, container padding L/R 6dp, checkbox paddingLeft 6dp; AI sub-row text 12sp, marginLeft 30dp | no equivalent AI sub-row (feature cut/different) | n/a | — |
| Our sheet panel width | MX has **no equivalent right slide-in sheet** in decoded layouts — subtitle/audio selection lives in the drawer/panels above. **No MX value exists** | sheetPanel initial 366dp, runtime width = **42% of screen**, min 260dp (buildSheets L2999–3025) | unmeasurable — our invention, retained from v0.3 recording | — |
| Sheet header | no MX equivalent | **25sp bold**, paddingBottom 12dp (sheetHeader L3072–3078) | unmeasurable | — |
| Sheet radio/check glyph | MX uses AppCompat CheckBox/RadioButton with `buttonTint` (subtitle_item*.xml) — stock Material sizes (radio Ø20dp class, checkbox 18dp class; not pinned in MX values) | MxRadioView: ring Ø ≈ **18dp** (r = 10dp − 1dp, stroke 2dp) + dot Ø10dp in 24dp box; MxCheckView: box **20×20dp**, stroke 2dp, radius 2dp (L3600–3700 class bodies) | radio ring −2dp vs Material 20dp class | Ø20dp ring |

### 1.6 Decoder dialog + floating bars (`dialog_options_menu.xml`, `*_bar.xml`)

| Element | MX value | Our value | Delta | Fix value |
|---|---|---|---|---|
| Options dialog container | `dialog_options_menu.xml`: paddingTop **12dp**; footer Cancel **16sp** tertiary (`mxskin__96a2ba…`), padding **10dp**, marginTop 4dp, marginRight 16dp; Done **16sp** accent `#3C8CF0`, padding **10dp**, marginRight 12dp; bottom placeholder **8dp** | decoder dialog: box padding **23/12/23/14dp**; footer Cancel 16sp TERTIARY pad 10dp, Done 16sp ACCENT bold pad 10dp, footer topMargin 4dp, bottom spacer 8dp (showDecoderDialog L5079–5178) | footer ✓ all values; box side padding 23dp has **no MX counterpart** (MX list is full-bleed RecyclerView) | list full-bleed; keep footer as-is |
| Dialog title | chapter dialog reference (`dialog_chapter.xml`): title **18sp bold**, marginTop 16dp, marginStart 24dp | "Select decoder" **20sp**, paddingBottom 8dp (L5094) | +2sp vs chapter reference (no decoder-specific title style exists in MX) | 18sp |
| Dialog row height | **not in any decoded layout** (RecyclerView rows are adapter-side) | **45dp** rows, radio 24dp box, label **18sp**, paddingLeft 16dp (L5127–5133) | row height unmeasurable for MX; label 18sp vs MX panel item 16sp | label 16sp |
| Floating bars (playback speed / subtitle speed / audio sync / subtitle sync) | `playback_speed_bar.xml` & siblings (identical shape): margins L/R/B **4dp**; header title **14sp**, paddingLeft 8dp; close button **42×42dp**, icon `ic_close_999_24dp` scaleType center; stepper row padding L/R 4dp; value EditText **15sp** `#CCCCCC`, minWidth **56sp**; unit **13sp**, minWidth 13sp; stepper buttons style `PlaybackFloatingButton` = **52×52dp** | showFloatBar: bottomMargin **124dp** (placement differs — MX bars sit at bottom, margin 4dp); header title 14sp padLeft 8dp ✓; close **42×42dp** ✓; value 15sp #CCCCCC minWidth 56dp ✓; unit 13sp minWidth 13dp ✓; steppers **42×42dp** text glyphs "−"/"+" 20sp (L3286–3370) | **stepper −10dp** (42 vs 52); bar bottom offset +120dp vs MX 4dp (ours floats above transport — deliberate while bar coexists with chrome; flag) | steppers **52×52dp** |
| Equalizer dialog | `dialog_equalizer.xml` is an **empty FrameLayout container** (content is code-side) | ours opens system/own EQ surfaces via menu | unmeasurable | — |

### 1.7 Player menu / drawer (`view_player_menu.xml`) + right rail (`player_right_menu_layout.xml`)

| Element | MX value | Our value | Delta | Fix value |
|---|---|---|---|---|
| Drawer container | **200dp wide × 360dp high** (`dp200`/`dp360`), bottom-right anchored (`view_player_menu.xml` `@id/container`, bg `player_menu_bg`) | menuPanel **300dp wide × MATCH height**, right (buildMenuPanel L2942–2970) | **+100dp wide, full-height vs 360dp** | **200×360dp** |
| Drawer back icon | **24×24dp** (`ic_back`), marginTop 16dp; portrait marginLeft 24dp | menu has text header "Menu" 16sp bold, padding 16/14/16/10 — no back icon at this level | structure differs | per decoded layout |
| Menu row height / text | drawer list content is code-side in MX (no row layout in decode) | rows: padding 16/11/12/11 → height ≈ **42dp**, label **15sp**, value 13sp, checkbox box 20×20dp marginRight 12dp (menuItemRow L4712–4745) | unmeasurable for MX | — |
| Menu section header | code-side | **12.5sp bold** SECONDARY, padding 16/12/16/6 (menuHeaderRow L4700–4702) | unmeasurable | — |
| Right rail buttons | **40×40dp**, padding **8dp** → glyph 24dp; gap marginTop **16dp**; container padding L/R **16dp**, bottom **112dp** (`dp112`) | **40×40dp**, padding **8dp**, topMargin **16dp**, padding 16/0/16/112 (buildRightRail L3157–3180) | 0 | ✓ MATCH |
| Right rail items | menu_1 = PiP (`ic_button_pip`), menu_2 = zoom (`ic_button_zoom`) | PiP (PIP_ENTER), zoom (ASPECT_CROP static glyph) (L3171–3173) | glyph semantics ✓; our zoom glyph does not cycle with mode (see §2.8 note) | — |

### 1.8 OSD elements

| Element | MX value | Our value | Delta | Fix value |
|---|---|---|---|---|
| Centre OSD text (supreme) | `supremeAppearance` style: **52sp**, Muli semibold, white, shadow radius 2 black (`styles.xml`; `screen.xml` supremeContainer centred) | aspectOsd **58sp**, DEFAULT_BOLD, centred (L2289) | **+6sp** | **52sp** |
| Centre OSD secondary text | `supremeAppearanceNew`: **14sp** Muli | indicatorView **19sp**, padding 18/10, rounded bg argb(170,…) (L2314–2316) | +5sp (different element class; MX supremeTop content is code-side) | 14sp class for top OSD |
| Brightness bar (the real MX one) | `brightbar.xml` (`BrightnessBar`): width **42dp**; level text **13sp** Muli semibold, marginTop 10dp; bar **4dp wide × 92dp tall**, marginTop 6dp, progress drawable `brightbar_progress.xml` (bg `#4dffffff` radius 2dp, progress `dark_sky_blue`); icon **20×20dp** (`ic_brightness`, intrinsic 21dp), marginTop 4dp, marginBottom 12dp; container marginRight **16dp**, right-aligned, vertically centred. Total height ≈ 10+13sp+6+92+4+20+12 ≈ **157dp** | VerticalBar **54×190dp** (L2298–2310, margins 18dp), drawn: label text **24px raw** (≈8dp at xxhdpi — Paint textSize is px, not dp-scaled), % text **30px raw**, track width 34% of 54dp ≈ **18.4dp wide**, corner radius 6px raw | **width +12dp, height +33dp; track ≈4.6× too wide (18.4 vs 4dp); margins +2dp; text sizes are raw px (density-dependent — a bug class of its own)** | **42 wide; bar 4×92dp; text 13sp; icon 20dp; margin 16dp** |
| Volume bar | `ScreenVerticalBar` (MASTER-SCENE-SPEC §P6; layout not separately decoded — same family as BrightnessBar, 1,000ms self-hide) | same VerticalBar 54×190dp, right side, margin 18dp | as above (MX exact box unmeasured, §5) | as above |
| Tap-seek overlay | `tap_seek_layout.xml`: icon **24×24dp** (`fast_rewind_24_px`/`fast_forward_24_px` — note rewind raster canvas is 30×30dp, ink 21.3×12.7dp), time text **18sp**, zones weighted 1:2:1 | tapSeekLeft/Right: **120dp-wide** full-height strips, text **16sp** "◂◂ 10s" with text-glyph arrows, bg argb(60,…) (tapSeekIndicator L3449–3455) | text −2sp; **no 24dp icon** (text triangles instead); zone model differs | 24dp icon + **18sp** text |
| Seek preview | no decoded layout found | preview image 176×99dp, text 15sp pad 14/7, topMargin 72dp (L2332–2337) | unmeasurable | — |
| Buffering | `screen.xml`: spinner wrap-content centred + text **14sp** (`sp14`) white Muli, marginTop = `margin_top_switch_new_player` (dimen not in dimens.xml extract — §5) | spinner **44×44dp**, text **13sp**, paddingTop 8dp (L2383–2387) | text −1sp | 14sp |
| Unlock button (locked state) | `lock.xml` referenced in spec §P6 but its geometry was not extracted in this pass | unlockBtn **50×46dp** centred, UNLOCK glyph box = full button (no padding) (L2430–2470) | unmeasurable for MX | — |
| Gesture overlays (brightness/volume/seek scrub) | thresholds/rates live in `ActivityScreen.onTouch` — **not decompiled** (MASTER-SCENE-SPEC §P1, Gap 14) | gesture handling in GestureListener (code) | **unmeasurable both sides from static resources** | — |

---

## 2. DEVICE EVIDENCE — Sir's real MX Player screenshots (POCO F5)

Files: `~/workspace/user/files/14940_6_vf10.png` and `14941_4_8xyv.png`, both 2048×921px. POCO F5 logical size 873×393dp ⇒ **1 screenshot px ≈ 0.4267dp**. Values below are the supplied visual-grounding measurements (±~2dp) verified against the images in this pass. **This section outranks decoded-layout inference wherever they conflict.**

| # | Element | MX screenshot value | Our code value | Delta / verdict |
|---|---|---|---|---|
| 2.1 | Quick-actions circle Ø | **≈44.5dp** (bbox 49–100/1000 wide, 225–338/1000 tall) | **40dp** disc (stripCircle ~L2627) | **−4.5dp.** CONFLICT: decoded `screen_shortcut_image.xml` says 40dp circle. Device (44.5 ±2dp) does not reach 40 even at tolerance edge (42.5). Record both; device wins for the fix target pending an on-device MX re-measure — fix value **44–45dp** if Sir confirms, else 40dp decoded. Do not silently pick one. |
| 2.2 | Pause glyph | **≈27dp wide × ≥28dp tall**, centred in transport row; consistent with `ic_button_pause` raster intrinsic 33×32dp (ink 23.0×26.7dp, §3) | MxIcon PAUSE at transport render: glyph box 24dp; bars RectF(6.5–10, 5–19) & (14–17.5, 5–19) on 24-grid ⇒ ink **11.0dp wide × 14.0dp tall** (two 3.5dp bars, 4dp gap) | **ink −16dp wide / −14dp tall vs device (≈41% of MX width, 50% of height).** MX bar proportions at intrinsic: ink spans x 5.0–28.0 of 33 (bars ≈8.7dp each, gap ≈5.7dp), y 2.7–29.3 of 32. Ours: bar width 3.5u vs MX ≈8.7dp-equivalent — our bars are ~2.5× too thin relative to height. **P1.** Fix: PAUSE ink ≈23×26.7dp in a 32–33dp glyph box (bars ≈8.7dp wide, gap ≈5.7dp, height 26.7dp). |
| 2.3 | Seek thumb Ø | **≈17dp** (bbox 283–303, 761–804 /1000) | **Ø17dp** (MxSeekBar radius 8.5dp) | **MATCH ✓** — record as verified, no fix. |
| 2.4 | Lock glyph | **≈19×22dp**, bottom-left of transport row | MxIcon LOCK at 24dp box: body RectF(4.5–19.5) = 15u wide; total y 2.5–21.5 = 19u ⇒ **15×19dp** | **−4dp wide / −3dp tall.** Fix: LOCK ink 19×22dp (scale glyph ≈1.27× within box, or reduce button padding for lock specifically). **P1-adjacent (P2 by grade rules — geometry; listed in P1 candidates because it is on the always-visible transport row).** |
| 2.5 | Top bar composition / battery & clock | BOTH shots: **back arrow · single-line title · music-note · subtitle (rect-with-lines) · plain text "HW" · ⋮**. **No battery icon, no clock inside MX's top bar** — 9:11 + battery "86" at the very top are the **phone's system status bar**, above the MX chrome | Our top bar: back · title · AUDIO_TRACK · SUBTITLE · decoder text · MORE (buildTopBar L2502–2555). **No battery/clock drawn** (removed v0.3, comment L4556–4560; `PSet.batteryClock` L594 is a Settings key only) | **MATCH ✓** — composition and order identical; no delta. Caveat: MX *has* a `clockBattery` menu item (`res/menu/screen.xml`) and a battery/clock setting, so this is **Sir's configuration**, not an MX invariant. Do not re-add battery/clock to the player top bar. |
| 2.6 | Seek bar + time labels | Hairline track, blue fill, blue dot thumb; current time at **LEFT END** of the bar's row, total time at **RIGHT END**, **same row as the bar** (15:47 left, 1:13:06 right in shot 1) | seekRow: curTime LEFT, MxSeekBar centre (weight 1), durTime RIGHT, same horizontal row (L2790–2810); track 2dp hairline, ACCENT fill, Ø17 thumb | **MATCH ✓** on placement, thumb, fill. Only open point: track thickness — device apparent ≈2dp agrees with ours; decoded drawable says 4dp (§1.4). Device wins: **keep 2dp**. |
| 2.7 | Quick-actions order + chevron | Order: **equalizer (sliders) · "1X" text · screenshot (camera-in-corners) · headphones · rotate/phone · then a SMALLER dark circle with ›**. Main circles ≈44.5dp; chevron circle visibly smaller (≈30dp, ≈0.67×) | Collapsed order from DEFAULT_RAIL (L731) ∩ pinned set (L2604): **equalizer · playback_speed ("1X"-style text) · screenshot · sticky · screen_rotation**, then chevron disc **24dp** (0.60× our 40dp main) | Order positions 1–3 **MATCH**; **position 4 DIFFERS — MX: headphones (background play); ours: sticky (pin glyph)**; position 5 **MATCH** (screen_rotation / ROTATE_PHONE tilted-phone glyph vs MX's tilted-rect-with-arrows — same concept, §3 kind 53). Chevron: both smaller-than-main ✓, ours 24dp vs device ≈30dp → **−6dp**. "1X" text: MX renders speed as text ✓ concept, size delta in §1.2 (+3sp). |
| 2.8 | Aspect button = NEXT mode's glyph | Shot 1 (14940): aspect button shows **"expand/arrows-in-rect" glyph** (↔ arrows inside a rect). Shot 2 (14941): **plain rounded-rectangle glyph**. Same PiP button beside it in both. This is device proof the button displays the **NEXT** mode in the cycle | `refreshAspectButton()` (L2901–2907) sets the glyph from `AspectCycle.next(zoomMode)` — **next-mode semantics ✓ MATCH**. Glyphs: our ASPECT_STRETCH = rect + ↔ arrows (L1529–1540) ≈ shot 1's glyph ✓; our ASPECT_100 = plain rect outline (L1553–1556) ≈ shot 2's glyph ✓ | **MATCH ✓** on mechanism and on both glyph shapes. One inconsistency: the right-rail zoom button uses a **static** MxIcon.ASPECT_CROP (L3172) and never follows the cycle — minor, P3. |
| 2.9 | Transport grouping + pause:prev/next ratio | Lock far left; **prev/pause/next centred as a group**; aspect + PiP far right. Centre-group pitch (glyph centre to glyph centre) ≈ **98–100dp** (prev ≈375/1000, pause ≈488/1000, next ≈598/1000 of 873dp width). Pause glyph ≈27dp wide vs prev/next glyphs ≈17–19dp wide → **ratio ≈1.5 : 1** | Groups: left cluster (lock+backward), centre cluster, right cluster (aspect+PiP) ✓ structure. But centre buttons are **contiguous 50dp** ⇒ pitch **50dp**. Glyph widths at render: pause ink **11.0dp**, NEXT ink **12.5dp**, PREV ink **12.5dp** → **ratio 0.88 : 1 (pause NARROWER than prev/next — inverted vs MX)** | **Pitch −48 to −50dp vs device (P1). Ratio inverted (P1).** Fix: centre pitch ≈99dp; pause glyph box 32dp with ink ≈23dp wide; prev/next ink ≈18dp (§3). Note the decoded layout alone (contiguous 50dp) contradicts the device — MX must add runtime spacing; trust the device. |
| 2.10 | Video area framing | Shot 14940 shows MX's video **NOT full-bleed** — a black frame surrounds the video frame | Ours is full-bleed under the cutout by design | **NOTE ONLY — NOT A FIX.** Sir's standing order for GhostBlade is full-bleed under the cutout. Recorded so nobody "fixes" this later. |

---

## 3. ICON GEOMETRY TABLE — every MxIcon kind (1–55)

**Reading guide.** "MX render" = size at which MX's player actually displays the glyph (intrinsic raster size centred in its button, or the layout size where a layout governs). "MX ink" = alpha-bbox of the xxhdpi raster ÷3 (dp), measured in this pass — this is the glyph's true optical size/centre. "Ours" = MxIcon drawing on the 24-unit grid (u = render-box/24); transport render box = 24dp, top-bar box = 24dp, strip box = 24dp, rail box = 24dp (40dp button − 8dp padding… rail padding 8dp ⇒ box 24dp). Base stroke 1.6u (=1.6dp at 24dp box) unless a kind overrides. Where MX has no equivalent glyph or no measured raster, that is stated — not guessed.

| Kind | MX drawable | MX intrinsic / viewport | MX ink (dp) & centre | MX render | Our drawing (24-grid) | Specific differences |
|---|---|---|---|---|---|---|
| 1 PLAY | `ic_button_play` (raster, level-list frame) | 32×32dp | 22.0×26.7, c=(16.33,16.00) — centre +0.33dp right (correct optical shift for a triangle) | 32dp in 50×46 button | triangle (8,5)-(19,12)-(8,19) fill: ink 11×14u | **Rendered at 24dp box ⇒ ink 11×14dp vs MX 22×26.7dp — half size.** No optical-centre shift issue (ours c=(13.5,12)u ≈ +1.5u right — proportionally larger shift). Fix: 32dp box, ink 22×26.7 |
| 2 PAUSE | `ic_button_pause` (raster) | 33×32dp | 23.0×26.7, c=(16.50,16.00) | 33×32dp | bars (6.5–10, 5–19) & (14–17.5, 5–19), r=1u: ink 11×14u | **Ink −12×−12.7dp; bars 3.5u vs MX ≈8.7dp-equiv; gap 4u vs ≈5.7.** Device confirms (≈27dp wide, §2.2). Fix per §2.2 |
| 3 NEXT | `ic_button_next` (raster) | 24×24dp | 18.0×18.0, c=(12,12) | 24dp | triangle (5,6)-(13,12)-(5,18) + bar (15–17.5, 6–18): ink 12.5×12u | **Ink −5.5×−6dp (69% of MX).** Proportions: MX triangle:bar gap smaller; ours bar starts 15u vs MX ink continuous 3–21. Fix: ink 18×18 (triangle (3,3)-(12,12)-(3,21) class + bar to 21u) |
| 4 PREV | `ic_button_prev` (raster) | 24×24dp | 18.0×18.0, c=(12,12) | 24dp | mirror: triangle (19,6)-(11,12)-(19,18) + bar (6.5–9, 6–18): ink 12.5×12u | as NEXT, mirrored. **Ink −5.5×−6dp** |
| 5 FORWARD | `ic_button_forward` (raster) | 24×24dp | 18.7×16.7, c=(12,12) | 24dp, button padding 8dp | arc 285° from −60°, r=7.5u box (4.5–19.5), stroke 1.6u + arrowhead (19.8,2.2)-(20.2,7.6)-(15.2,5.4) | MX glyph is a **double-triangle fast-forward** class raster (ink 18.7×16.7 squarish), ours is a **circular-arrow** — different glyph concept for the ±seek button. MX's overlay numeral (10sp) carries the meaning in both. **P2 concept mismatch** |
| 6 BACKWARD | `ic_button_backward` (raster) | 24×24dp | 18.7×16.7, c=(12,12) | 24dp, padding 8dp | mirror arc −120°, 285° + arrowhead (4.2,2.2)-(3.8,7.6)-(8.8,5.4) | as FORWARD, mirrored |
| 7 LOCK | `ic_button_locks` level-list → `ic_unlock_white_24dp` (level 0) | 24×24dp | 16.0×21.3, c=(12,12) | 24dp | shackle arc (7–17, 2.5–11) stroke 1.6u + body round-rect (4.5–19.5, 10.5–21.5) fill, keyhole cut r1.7: ink 15×19u | Ink −1×−2.3dp vs raster at same box — **but device measures MX lock at 19×22dp (§2.4)**, i.e. MX renders it larger than intrinsic on device. Ours 15×19 ⇒ −4×−3 vs device |
| 8 UNLOCK | `ic_button_unlock_plus` (level 1) | 24×24dp | 20.0×21.3, c=(14.00,12.00) — ink touches right edge, centre +2dp right | 24dp | open shackle arc (7–17, 2.5–11, −140° from 0°) + same body: ink ≈15×19u | Ours centred; MX ink is right-shifted (open shackle swings right). −5dp ink width |
| 9 ROTATION | `ic_button_rotation_locked/unlocked` (levels 2/3) | 24×24dp | locked 22.0×19.3 c=(11.00,11.67); unlocked 21.0×18.0 c=(10.50,12.00) | 24dp | = FORWARD circular arrow (arc + arrowhead) | Concept differs (MX = phone-with-lock class glyph, ink nearly full-bleed 22dp wide, centre −1 to −1.5dp left); ours is a generic rotate arrow, ink ≈16×17.3u. P2 |
| 10 AUDIO_TRACK | `ic_audiotrack_white_24dp` (menu icon) | 24×24dp | 13.0×18.0, c=(12.50,12.00) | 24dp in toolbar slot | quaver: head oval (5–10.5, 15.5–20), stem (9.3–10.8, 5–17), flag quad to (15.5,9–12): ink ≈10.5×15u | Ink −2.5×−3dp; ours head is an ellipse 5.5×4.5u vs MX note-head smaller/rounder; MX centre +0.5dp right, ours ≈ centred. P2/P3 |
| 11 SUBTITLE | `ic_chat_white_24dp` (menu icon) | 24×24dp | 20.0×20.0, c=(12,12) | 24dp in toolbar slot | round-rect (3–21, 5.5–18.5) stroke 1.6u r2 + lines (6–18, 9.5–11) & (6–13.5, 13.5–15) fill: ink 18×13u | **Ink height −7dp** (ours card is 13u tall vs MX 20dp square-ish speech card); MX glyph fills its canvas (2dp margins), ours has 5.5u top margin. Fix: card (2–22, 2–22) class. P2 |
| 12 MORE | `ic_more_white_24dp` | 24×24dp | dots column: 4.0 wide × 16.0 tall, c=(12,12) → dot Ø≈4dp, pitch ≈6dp | 24dp | 3 dots r=2u (Ø4u) at cy 5.5/12/18.5 → pitch 6.5u, ink 4×17u | **Near-match**: dot Ø ✓ 4; pitch +0.5u; total span 17 vs 16. P3 |
| 13 BACK | `ic_back` (raster) | 24×24dp | 15.7×15.3, c=(12.17,12.00) | 24dp (toolbar nav / drawer) | Material arrow path (19,11)-(8.6,11)-(13.4,6.2)…(19,13) fill: ink 10.4×7.6u shaft+head | **Ink −5.3×−7.7dp.** Ours is the stock Material arrow silhouette at 24-grid scale but compressed vertically (head spans 6.2–17.8 = 11.6u vs MX 15.3). P2 |
| 14 CLOSE | `ic_close_999_24dp` (floating bars; raster, xxhdpi not extracted — catalogue lists 24dp class) | 24×24dp (named) | not measured (file not in xxhdpi extract) | 24dp centred in 42dp button (scaleType center) | X: lines (6,6)-(18,18) & (18,6)-(6,18), stroke **2.2u**: ink ≈13.7×13.7u incl. stroke | Stroke 2.2u is heavier than the 1.6u system; MX ink unmeasured — P3, verify at build |
| 15 PIP | `ic_button_pip` (raster) | 24×24dp | 22.0×18.0, c=(12,12) | 24dp (zb_right & right rail) | round-rect (3.5–20.5, 5–19) stroke 1.6u + solid rect (12.5–18, 12.5–16.5): ink 17×14u | Ink −5×−4dp; MX outer rect spans nearly full width (1dp margins), ours 3.5u margins. Fix: rect (1–23, 3–21) class. P2 |
| 16 BRIGHTNESS | `ic_brightness` | 21×21dp | 20.3×20.3, c=(10.17,10.17) — ink essentially full-bleed | 20×20dp in BrightnessBar (scaleType centerCrop) | sun: half-disc fill + circle r4u stroke + 8 rays, ray stroke 1.4u: ink ≈19×19u at 24 box | Rendered in OSD contexts, not a button; geometry concept ✓ (sun). At 20dp display ours scales to ≈15.8dp ink vs MX 20.3 in 20dp view (centerCrop fills). P3 |
| 17 VOLUME | no dedicated MX player raster identified in catalogue extract | — | not measured | — (OSD is the bar, §1.8) | speaker path (4,9.5)-(7.5,9.5)-(12.5,5)-(12.5,19)-(7.5,14.5)-(4,14.5) + 2 arcs stroke 1.6u: ink ≈17.5×14u | MX counterpart unmeasured — P3/unmeasurable |
| 18 VOLUME_MUTE | as 17 | — | not measured | — | speaker + X lines (15,9.5)-(20.5,15) stroke 1.8u | as 17 |
| 19 SCREENSHOT | shortcut raster (screenshot is a screen-shortcut; catalogue player group) — individual entry not extracted in this pass | 24×24dp (shortcut glyph standard, `screen_shortcut_img` dp24) | not measured | 24dp in 40dp circle | viewfinder brackets (3.5–20.5) stroke 1.7u + centre circle r3.4 stroke: ink 17×17u | Device shot shows camera-in-corners glyph ✓ same concept as ours. Ink size unverified vs MX raster. P3 |
| 20 PLAYLIST | `ic_video_playlist_white` (menu `@id/playlist`) | 25×24dp | 20.3×13.0, c=(12.83,12.50) | 25dp in menu slot | lines (3.5–12, 6–7.8) & (3.5–12, 15.2–17) + triangles (4,9.8)-(9,12.3)-(4,14.8) & (14.5,9.5)-(20.5,12.3)-(14.5,15): ink 17×11.2u | Concept differs in detail (MX = lines + single play at right, ink 20.3 wide); ours adds a second small triangle left. −3.3dp ink width. P2/P3 |
| 21 CHECK | MX uses AppCompat checkbox (no custom glyph) | stock | — | — | tick path (4.5,12.8)-(9.8,17.8)-(19.5,6.5), stroke 2.4u | Ours is a standalone tick (menu/sheet use MxCheckView instead, §1.5). P3 |
| 22 ZOOM_IN | `ic_button_zoom` level-list frames are aspect glyphs, not magnifiers — MX has **no magnifier** on the player (zoom = pinch + zb_left) | — | — | — | magnifier circle r6u at (10.5,10.5) + handle + plus | **No MX counterpart** — our invention for zoom UI. Not a misalignment; flag as non-MX glyph wherever shown |
| 23 ZOOM_OUT | as 22 | — | — | — | magnifier + minus | as 22 |
| 24 SPEED | MX strip shows speed as **TEXT** ("1X", §1.2) — no speed glyph in strip | — | — | text | gauge: semicircle arc (4–20, 6–20) + needle to (16.5,8.5) | Glyph used elsewhere in ours (menus); MX's strip treatment is text. P3 |
| 25 SLEEP | shortcut/menu glyph not individually extracted | 24dp class | not measured | 24dp | crescent: circle r8u DIFFERENCE circle r6.6u at (15.5,9) | unverified vs MX raster. P3 |
| 26 NIGHT | no MX player counterpart identified (MX has no night-mode player glyph in catalogue extract) | — | — | — | crescent r7u + 2 star dots | non-MX feature glyph; P3/none |
| 27 MIRROR | no MX counterpart identified in player set | — | — | — | centre line + 2 facing triangles | as 26 |
| 28 FLIP | as 27 | — | — | — | horizontal line + up/down triangles | as 26 |
| 29 EQUALIZER | device strip item 1 = vertical sliders glyph (§2.7); raster not individually extracted | 24dp class | not measured | 24dp in 40dp circle | 3 vertical lines x=7/12/17 (4.5–19.5) stroke 1.7u + knobs r2.1 at y 9/14.5/7.5 | Concept ✓ matches device (sliders). Sizes unverified. P3 |
| 30 REPEAT_AB | MX menu item `@id/repeat_ab` is text-only (menu/screen.xml) | — | — | — | "A-B" text 9.5u bold centred | MX has no glyph; ours invents one. P3 |
| 31 SHUFFLE | MX menu item checkable text-only | — | — | — | crossing cubics + 2 arrowheads, stroke 1.7u | as 30 |
| 32 LOOP | MX loop items are checkable text menu items | — | — | — | 2 arcs (130° each) + 2 arrowheads | as 30 |
| 33 STICKY | MX `sticky` is a checkable menu item (text); **device strip position 4 is headphones, not sticky** (§2.7) | — | — | 24dp in strip circle | pin path (9,3.5)-(15,3.5)…(17,15)-(7,15) + stem (11.2–12.8, 15–20.5) | Glyph itself unverified; **its collapsed-strip placement is the delta** (§2.7) |
| 34 CHEVRON_RIGHT | device: › inside ≈30dp circle (§2.7) | — | not measured (code-side in MX) | ≈30dp disc | chevron path (9,5)-(16,12)-(9,19), stroke 2.2u, in 14dp glyph box (24dp disc − 5dp margins) | Disc −6dp vs device; glyph 14dp box vs MX disc 30dp ⇒ glyph ≈ −8dp class. P2 |
| 35 ASPECT_FIT | `ic_zoom_inside` (level-list frame 1) | 24×24dp | 22.0×18.0, c=(12,12) | 24dp | round-rect (3–21, 6–18) stroke 1.7u + 2 inner bars (8.6–10.4 & 13.6–15.4, 8.6–15.4): ink 18×12u | MX frame ink 22×18 (rect spans full width, 3–21 vertically in dp terms 3–21 of 24). Ours rect height 12u vs MX 18. **All four zoom frames share one ink bbox in MX** (identical 3,9–69,63) — the frames differ inside, not in outline. Fix: rect (3,3)-(21,21) class outline. P2 |
| 36 ASPECT_STRETCH | `ic_zoom_stretch` (frame 0) | 24×24dp | 22.0×18.0, c=(12,12) | 24dp | rect (3–21, 6–18) + ↔ line (6.5–17.5, 12) + 2 arrowheads | Device shot 1 glyph ≈ this concept ✓ (§2.8). Outline size delta as kind 35 |
| 37 ASPECT_CROP | `ic_crop_white_24dp` (frame 3) | 24×24dp | 22.0×18.0, c=(12,12) | 24dp | rect (3–21, 6–18) + diagonal (9,15)-(15,9) + 2 corner arrowheads | Outline size delta as kind 35 |
| 38 ASPECT_100 | `ic_zoom_original` (frame 2) | 24×24dp | 22.0×18.0, c=(12,12) | 24dp | plain round-rect (3–21, 6–18) stroke 1.7u | Device shot 2 glyph = plain rect ✓ concept (§2.8); ours rect is 18×12u vs MX ink 22×18. P2 |
| 39 PENCIL | no MX player counterpart identified | — | — | — | diagonal pencil outline (4.5,19.5)-(19.8,7.4) stroke 1.6/1.4u | non-MX editor glyph. P3 |
| 40 HEADPHONES | device strip item 4 (§2.7); raster not individually extracted | 24dp class | not measured | 24dp in strip circle | band arc (4.5–19.5, 4–17) stroke 1.8u + pads (3.8–7.6 & 16.4–20.2, 12.5–18.5) fill | Concept ✓. **Placement is the fix: this kind belongs at collapsed position 4** (§2.7) |
| 41 STOPWATCH | not individually extracted | 24dp class | not measured | 24dp | circle r7.5u at (12,13.5) stroke 1.8u + crown + hands | unverified. P3 |
| 42 WAVEFORM | not individually extracted | 24dp class | not measured | 24dp | 5 vertical bars stroke 1.9u, heights 4–15u | unverified. P3 |
| 43 FOLDER | `ic_folder_white_24dp` (subtitle panel load button) | 24×24dp | 20.0×16.0, c=(12,12) | 24dp in 48dp panel bar | open-folder outline path (3,6.5)-(21,8.8)-(19.4,18)-(5.4,18)-(3,9.5) stroke 1.7u: ink 18×11.5u | Ink height −4.5dp (ours shallower); MX is a filled closed-folder silhouette class, ours an open outline. P2/P3 |
| 44 QUEUE | not individually extracted | 24dp class | not measured | 24dp | 3 lines + triangle (13.5,13.8)-(20.5,17.3)-(13.5,20.8) | unverified. P3 |
| 45 SLIDERS_H | not individually extracted | 24dp class | not measured | 24dp | 2 horizontal lines + 2 knobs r2.6 | unverified. P3 |
| 46 BOOKMARK | not individually extracted | 24dp class | not measured | 24dp | ribbon outline (7,4)-(17,4)-(17,20)-(12,15.8)-(7,20) stroke 1.7u | unverified. P3 |
| 47 CUT | MX clip editor is a separate activity (out of player scope) | — | — | 24dp | frame + play triangle | out-of-scope glyph. P3 |
| 48 HEART | not individually extracted | 24dp class | not measured | 24dp | heart outline cubics, stroke 1.8u, ink ≈16.8×15.6u | unverified. P3 |
| 49 LIST_PLUS | not individually extracted | 24dp class | not measured | 24dp | 3 lines + plus (stroke 2u) | unverified. P3 |
| 50 LIST | not individually extracted | 24dp class | not measured | 24dp | 3 bullets r1.5 + lines (9.5–20) | unverified. P3 |
| 51 SHARE_ARROW | MX Share is **cut** by Sir's standing order | — | — | 24dp | curved arrow stroke 1.9u + head | Should not appear in player at all (cut feature). Flag if reachable |
| 52 BULB | not individually extracted | 24dp class | not measured | 24dp | bulb circle r6.3 stroke 1.7u + base rects | unverified. P3 |
| 53 ROTATE_PHONE | device strip item 5: tilted rect with circular arrows (§2.7) | 24dp class | not measured | 24dp in strip circle | phone round-rect (8.2–15.8, 4.5–19.5) rotated 45° + 2 orbit arcs (2.8–21.2) | Concept ✓ matches device. Sizes unverified. P3 |
| 54 PIP_ENTER | `ic_button_pip_more` (rail variant) | 28×28dp | 22.3×18.3, c=(13.50,14.50) — centre +0.5dp low | 28dp (rail `_more` variant) | outer rect (3–21, 5–19) stroke 1.7u + inner rect (13–19, 12.5–17) stroke + diagonal arrow | Our subnav PiP uses this kind at 24dp box; MX subnav uses `ic_button_pip` 24dp (kind 15's row) and the 28dp `_more` belongs to the rail/menu context. Ink proportions similar class. P3 |
| 55 ASPECT_RATIO | menu/dialog glyph, not a transport glyph | — | not measured | menu contexts | rect (4–20, 5–19) stroke 1.7u + filled block (6.2–12.5, 7.2–12.5) | unverified. P3 |

**Cross-cutting icon-system deltas (apply to nearly every kind):**
- **Stroke ratio:** ours 1.6u on a 24-grid = **6.7% of glyph box** for every stroke kind, one weight for all. MX rasters vary per glyph (e.g. MORE dots are solid Ø4dp in a 24dp canvas = 16.7% feature size; lock body is solid fill). Single-weight stroke rendering is why our glyphs read "thinner" than MX's even where ink boxes match.
- **Optical centres:** MX play ink centre is +0.33dp right of canvas centre (correct triangle optics); unlock +2.0dp right; audiotrack +0.5dp right; rotation-locked −1.0dp left / −0.33dp up. Ours are grid-centred except PLAY (+1.5u ≈ proportionally 4.5× MX's shift). Per-kind centres are in the table.
- **Padding inside viewBox:** MX raster canvases carry 1–3dp transparent margins (next/prev ink 18 of 24 = 3dp margins; pip/zoom ink 22 wide = 1dp margins). Ours typically inks 12–18u of 24 (3–6u margins) — systematically more padding, i.e. smaller-looking glyphs at identical box sizes.

---

## 4. P1 / P2 / P3 CANDIDATES — player only

### P1 — visible misalignment / wrong size / inverted proportions
1. **Pause glyph half-size, proportions inverted vs prev/next.** Device: pause ≈27dp wide, prev/next ≈17–19dp (ratio ≈1.5). Ours: pause ink 11.0dp, next/prev ink 12.5dp (ratio 0.88). (§2.2, §2.9, §3 kinds 2–4.) Fix: play/pause glyph box 32dp, PAUSE ink 23×26.7dp (bars ≈8.7dp, gap ≈5.7dp).
2. **Play glyph half-size.** MX ink 22×26.7dp at 32dp intrinsic; ours 11×14dp at 24dp box. (§3 kind 1.)
3. **Centre-group pitch ≈50dp vs device ≈98–100dp.** Prev/play/next read crowded together and the group is ~half MX's width on Sir's phone. (§2.9.) Fix: ≈99dp pitch.
4. **Title 17.5sp vs MX 14sp** (+25%) — title visibly larger/heavier than MX and crowds the right-side items on long filenames. (§1.1.)
5. **Brightness/volume OSD built wrong in every dimension:** ours 54×190dp with an ≈18.4dp-wide track and raw-px text (24px/30px Paint sizes, density-dependent); MX BrightnessBar is 42dp wide with a **4×92dp** bar, 13sp level text, 20dp icon, 16dp screen margin. (§1.8.) This is the OSD Sir sees on every brightness/volume gesture.
6. **Drawer size:** ours 300dp × full-height vs MX container **200×360dp**. (§1.7.)
7. **Collapsed strip 4th item wrong:** ours shows **sticky (pin)**; Sir's MX shows **headphones (background play)**. (§2.7.) Fix: pinned set position 4 = background-play.

### P2 — icon geometry differences
1. NEXT/PREV ink 12.5×12 vs MX 18×18 (−31% linear). (§3 kinds 3–4.)
2. LOCK ink 15×19 vs device 19×22. (§2.4.)
3. SUBTITLE card ink height 13u vs MX 20dp (card too flat). (§3 kind 11.)
4. BACK ink 10.4×7.6 vs MX 15.7×15.3 (vertically compressed). (§3 kind 13.)
5. PIP outer rect ink 17×14 vs MX 22×18. (§3 kind 15.)
6. Aspect glyphs (35–38): outline rect 18×12u vs MX frame ink 22×18 — all four read small/flat; device confirms the two shapes in use (stretch, 100%) are otherwise right. (§2.8, §3.)
7. FORWARD/BACKWARD concept: ours circular-arrow vs MX double-triangle raster class (ink 18.7×16.7). (§3 kinds 5–6.)
8. ROTATION concept: ours generic rotate-arrow vs MX phone+lock glyph (ink 22×19.3, centre −1dp left). (§3 kind 9.)
9. Floating-bar steppers 42×42 vs MX `PlaybackFloatingButton` 52×52. (§1.6.)
10. Chevron disc 24dp vs device ≈30dp; chevron glyph box 14dp. (§1.2, §3 kind 34.)
11. Strip "1X" text 15sp vs MX 12sp; red-dot badge 8dp vs MX 10dp (image variant). (§1.2.)
12. Seek track 2dp vs decoded drawable 4dp — **device evidence (≈2dp) contradicts the drawable; device wins, downgrade to P3-resolve-at-build.** (§1.4, §2.6.)
13. AUDIO_TRACK ink −2.5×−3dp and note-head shape; PLAYLIST second triangle not in MX glyph; FOLDER open-outline vs MX filled silhouette (ink height −4.5dp). (§3 kinds 10, 20, 43.)
14. Sheet radio ring Ø≈18dp vs Material/MX checkbox class Ø20dp. (§1.5.)

### P3 — fine fidelity
1. MORE dots: pitch 6.5u vs MX ≈6dp; span 17u vs 16dp. (§3 kind 12.)
2. Centre OSD text 58sp vs MX supreme 52sp. (§1.8.)
3. Seek-row label↔bar gap: ours 10dp margins; MX layout has the bar directly adjacent to labels. (§1.4.)
4. Seek view height 36dp vs MX label-driven row (≈28dp). (§1.4.)
5. Decoder dialog: title 20sp vs 18sp chapter reference; row label 18sp vs MX panel-item 16sp; box side padding 23dp with no MX counterpart. (§1.6.)
6. Buffering text 13sp vs MX 14sp. (§1.8.)
7. Tap-seek: text 16sp vs MX 18sp; text-triangle glyphs instead of MX's 24dp fast-forward/rewind icons. (§1.8.)
8. Right-rail zoom glyph static (ASPECT_CROP) while the transport aspect button cycles. (§2.8.)
9. Top-bar button slots 48dp in a 56dp bar (glyphs sit 4dp high of bar centre class). (§1.1.)
10. Subtitle/audio sheet rows 45dp vs MX panel item ≈48dp. (§1.5.)
11. Unverified-glyph set (kinds 14, 16–18, 19, 25, 29, 39–42, 44–46, 48–50, 52, 55): drawn from the v0.4 frame spec, not from measured MX rasters — each needs a raster-ink pass before it can be called faithful. (§3.)
12. Non-MX glyphs in the set (ZOOM_IN/OUT magnifiers, REPEAT_AB/SHUFFLE/LOOP text-menu concepts, NIGHT/MIRROR/FLIP, SHARE_ARROW for a cut feature): not misalignments, but they are not MX geometry and should not be presented as such. (§3.)

**Verified MATCHES (no action):** transport button boxes 50×46dp + padders 12/15dp + lock/PiP 8dp margins (§1.3); strip height 50dp, cell pitch 56dp, circle 40dp per decoded layout, glyph 24dp, label 10sp/5dp (§1.2); seek thumb Ø17dp (§2.3); bookmark dots Ø4dp #99ffffff (§1.4); time labels 12sp + 10dp top padding, same-row placement (§1.4, §2.6); ±seek overlay text 10sp (§1.3); subtitle panel bar 48dp + 16dp button padding (§1.5); right rail 40dp/8dp/16dp/112dp (§1.7); decoder-dialog footer (16sp, 10dp padding, 4dp top margin, 8dp placeholder) (§1.6); floating-bar header/value/unit sizes (§1.6); top-bar composition incl. **no battery/clock** (§2.5); aspect next-mode mechanism + both device-seen glyph shapes (§2.8).

---

## 5. HONEST LIMITS — what could NOT be measured, and why

1. **MX transport spacing at runtime.** `screen_subnavibar.xml` chains the centre buttons contiguously (50dp pitch), but Sir's device shows ≈98–100dp pitch. The extra spacing must be applied in `ActivityScreen` code (margins/paddings set programmatically); that code path was not decompiled in this pass, so the pitch fix value (≈99dp) rests on screenshot measurement (±~4dp), not on a decoded constant.
2. **Quick-actions circle conflict.** Decoded layout says 40dp; device measurement says ≈44.5dp (±2dp). Both are recorded in §1.2/§2.1; static sources cannot resolve which governs Sir's build (MX may scale shortcut items in the RecyclerView adapter, which is R8-obfuscated and unidentified — MASTER-SCENE-SPEC Gap 13). Needs one on-device MX measurement to close.
3. **MX BookmarkSeekBar thumb/track drawing.** The bar is a custom View (`progressDrawable=@null`); its onDraw constants live in smali (`BookmarkSeekBar.smali` exists in the decode but was not disassembled for constants in this pass). Thumb Ø17dp rests on device evidence; track thickness rests on the related drawable (4dp) vs device (≈2dp) — unresolved between sources, flagged in §1.4.
4. **Toolbar menu text appearance for the decoder label** ("HW"): the item is text-only in `res/menu/screen.xml`; its rendered size follows the toolbar theme's menu text appearance, which is not pinned to a single sp value in the decoded values extract. Ours (15.5sp) is compared against the 14sp title class only.
5. **Sheet/panel row heights for subtitle & audio in MX's drawer panels** are adapter-side (RecyclerView); the 48dp figure in §1.5 is computed from `item_audio_subtitle_landscape.xml` (16sp text + 12dp vertical padding), not a declared row height. Our own sheet (42% width, 25sp header) has **no MX counterpart at all** — it is a v0.3-recording invention, so no delta can exist.
6. **Volume `ScreenVerticalBar` exact box** and the **lock screen (`lock.xml`) geometry**: referenced in MASTER-SCENE-SPEC §P6 but their layouts were not extracted in this pass; only `brightbar.xml` was measured directly. Brightness numbers are exact; volume numbers are inferred from the shared family and marked as such.
7. **Gesture thresholds and OSD timing internals** (touch slop, seek rate, volume step, supreme OSD hold time): inside `ActivityScreen.onTouch`, explicitly not decompiled (MASTER-SCENE-SPEC Gap 14). Not measurable from static resources on either side.
8. **Raster ink for 15 MxIcon kinds** (§4 P3 item 11): the corresponding MX rasters were not individually extracted from the catalogue/xxhdpi set in this pass, so those rows record intrinsic class (24dp) only. Their "unverified" status is stated per-row rather than filled with guesses.
9. **`margin_top_switch_new_player`** (buffering text marginTop in `screen.xml`) is referenced but its value was not present in the dimens extract used; buffering margin is therefore unmeasured for MX.
10. **Font metrics:** MX uses the Mulish (Muli) family throughout the player; GhostBlade uses the platform default + DEFAULT_BOLD. Text *sizes* are compared exactly, but rendered text *widths/heights* will differ slightly at identical sp because the typefaces differ — no static measurement in this pass can quantify that per-string.
