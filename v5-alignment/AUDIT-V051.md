# GhostBlade Player v0.5.1 — Independent Audit (Shadow Assist Team, 1 agent, read-only)

**Date:** 2026-11-22 · **Scope:** the three quick-actions strip fixes for Sir's
"icons still not aligned" device report on v0.5 (screenshots
`14969_0_y9cy.png` expanded, `14968_1_ih77.png` collapsed), a whole-source
sweep for the same defect pattern, and an independent APK spot-check.
JVM suites were NOT re-run (Jervis ran them in parallel).

## Findings

**No P1 findings. No P2 findings.** All three claimed fixes are present in
source exactly as described, the layout maths checks out, the whole-source
sweep found no other instance of the defect pattern, and the APK matches
every claimed fact. P3 notes are at the end.

---

## Check 1 — The three fixes, with line evidence

All in `src/com/ghostblade/player/PlayerActivity.kt`.

### FIX 1 — stripCircle glyph centring: VERIFIED
- Wrap is 40×40dp (`stripCircle`, wrap `layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))`, ~L2640); the disc fills it exactly: `wrap.addView(disc, FrameLayout.LayoutParams(dp(40), dp(40)))` — **L2651**, no margins.
- Glyph ImageView now: `FrameLayout.LayoutParams(dp(24), dp(24))` with `leftMargin/topMargin/rightMargin/bottomMargin = dp(8)` — **L2667–2674**.
- **Maths:** 8 + 24 + 8 = 40 = wrap size exactly; glyph centre = 8 + 24/2 = **(20, 20)** = disc centre. No overflow, no clipping. The in-code comment (L2668–2672) documents the v0.5 defect (40dp box + 8dp margins → centre pushed to (28,28), bottom clipped), consistent with the brief's root cause and with Sir's screenshots (glyphs sat low, "1X" text centred).

### FIX 2 — chevron centring: VERIFIED
- Chevron frame is 30×30dp (~L2713–2717, with a 13dp left spacing margin on the frame itself — row spacing, outside the disc); disc fills it: `chevron.addView(chevronDisc, FrameLayout.LayoutParams(dp(30), dp(30)))`.
- Glyph ImageView now: `FrameLayout.LayoutParams(dp(14), dp(14))` with 8dp margins on all sides — **L2722–2727**.
- **Maths:** 8 + 14 + 8 = 30 = frame size; glyph centre = 8 + 14/2 = **(15, 15)** = disc centre. ✓

### FIX 3 — expanded strip height: VERIFIED
- `stripBox.addView(stripExpandedScroll, LinearLayout.LayoutParams(MATCH, WRAP))` — **L2606** (was a fixed 50dp per the in-code comment, L2602–2605).
- `stripExpandedRow` padding `(0, dp(2), 0, dp(6))` — **L2595**.
- **Cell-height maths:** each expanded cell (`rebuildStrip`, ~L2733–2745) is a vertical LinearLayout, 56dp wide, WRAP height, containing the 40dp circle + a label TextView (10sp, `maxLines = 2`, paddingTop 5dp). Cell height = 40 + 5 + ~13 (1 line) ≈ **58dp**, worst case 40 + 5 + ~26 (2 lines) ≈ **71dp**; plus row padding 2+6 → scroll content ≈ **66–79dp**. Because the scroll height is now WRAP_CONTENT it sizes to the full cell — labels can no longer be clipped to slivers. ✓ (Brief's ~73dp cell estimate sits inside this range.)

### Version bump: VERIFIED in source
- `AndroidManifest.xml` L4–5: `android:versionCode="7"`, `android:versionName="0.5.1"`.

## Check 2 — Whole-source sweep for the same defect pattern

Swept all 16 `.kt` files in `src/com/ghostblade/player/`. MxIcon/GlyphDrawable
glyphs exist ONLY in `PlayerActivity.kt` and `MainActivity.kt`; the other 14
files contain **no** ImageView/ImageButton/compound-drawable usage at all
(grep for `ImageView|ImageButton|setCompoundDrawables|setImageResource|setImageBitmap`
returns nothing there), and the only `setMargins` outside the two glyph files
are ClipEditorActivity L164/L199 — spacing on a VideoView card and a pill
container, not glyphs. **Fine.**

Every glyph site in the two glyph files was inspected with its LayoutParams
and margins. Candidates and judgements:

| Site | Pattern | Judgement |
|---|---|---|
| PlayerActivity chromeButton (L2563–2571) | ImageButton 48×48, padding 12, no margins | Fine — padding-centred 24dp glyph |
| PlayerActivity unlockBtn (L2422, added L2458) | Single ImageButton 50×46 at Gravity.CENTER; drawable centred by the view itself; no inner view, no margins | Fine — not the pattern |
| PlayerActivity subnavButton / playBtn / aspectBtn (L2837–2913) | 50×46 ImageButtons, padding (13,11) / (9,7); playBtn's transportGap margins sit on the button inside the nav row (pitch spacing) | Fine — padding-centred; margins are between buttons, not insets in a same-size parent |
| PlayerActivity backCell overlay (L2863–2867) | Button and interval TextView both 50×46 in one FrameLayout cell, no margins; text gravity CENTER | Fine |
| PlayerActivity railButton (L3214–3225) | 40×40, padding 8; zoom's topMargin 16 is rail spacing | Fine |
| PlayerActivity subPanelBar load/close (L3246–3271) | WRAP×MATCH ImageButtons, padding (16,12) | Fine |
| PlayerActivity float-bar close (L3362–3368) | ImageButton 42×42, no padding — the view centres its own drawable; matches MX's 42dp button from the v0.5 spec | Fine |
| PlayerActivity iconButton (L3483–3491) | 44×44, padding (9,7) | Fine |
| PlayerActivity sheet "Open" row icon (L6474–6477) | ImageView 26×26, no margins, no disc behind it | Fine |
| PlayerActivity, all other margin sites (OSD containers L2303/2309, seek preview pills L2345/2372, seek bar L2828, sheet divider L3192, chips L3305, float-bar position L3422, menu checkbox L4787, subtitle offset L6199, sheet scroll L6513, collapsed-strip cell spacing L2701–2702, chevron frame spacing L2715, lock/pip spacing L2858/2913) | Margins position containers or space siblings; none is an inset on a glyph inside a same-sized disc | Fine |
| MainActivity barIconButton (L354–362) + viewToggleBtn (L529) | 48×48, padding 12 | Fine |
| MainActivity quick-access circle (L393–398) | ImageView 44×44 in a 44×44 circle, NO margins, padding 10 → 24dp glyph centred | Fine — same size as parent but padding-based, which is the correct mechanism |
| MainActivity bottom-nav tab icon (L440–444) | ImageView 24×24 in a vertical cell, gravity CENTER_HORIZONTAL, no margins | Fine |
| MainActivity FAB glyph (L675–679) | ImageView 24×24, Gravity.CENTER in the 48×48 FAB, no margins | Fine |
| MainActivity folder list icon (L1745–1752) + badge | 76×76 ImageView fills its wrap, no margins; badge END-anchored (v0.5 fix, re-confirmed) | Fine |
| MainActivity folder grid icon (L1844–1849) | 62×62 with SYMMETRIC 8dp side margins in a wider grid cell (decoded spec values) | Fine — symmetric, and the parent is not a same-size disc |
| MainActivity thumb overlays: PLAY (L1929–1936) Gravity.CENTER no margins; HEART (L1964–1972) and selected-check (L2004–2010) small boxes at TOP\|END with 2–4dp corner margins on the full thumb frame | Corner-badge positioning on a much larger parent | Fine |
| MainActivity status icon (L646–652), info-row mini icon (L2068–2071) | 24×24 / 12×12 in rows; the 18dp leftMargin is row spacing | Fine |
| MainActivity "more" dots, list (L2094–2100) and grid (L2140–2147) | ImageViews 40×38 / 38×28 with deliberately asymmetric padding from the decoded MX row spec; no background disc to centre against | Fine — judged not the defect pattern (padding shapes the touch slot; there is no disc centre to miss) |

**Sweep result: zero additional instances of the defect pattern.**

## Check 3 — "1X" text and audioEffect red dot: UNAFFECTED ✓

- Speed item is a separate branch (`stripCircle`, ~L2658–2661): a TextView with `FrameLayout.LayoutParams(dp(40), dp(40))`, `gravity = Gravity.CENTER`, no margins — Fix 1 touched only the glyph branch's ImageView params (L2667). The text path is byte-for-byte the v0.5 behaviour that already rendered centred on Sir's device.
- Red dot is a separate `addView` (**L2685–2688**): 10×10dp, `Gravity.TOP or Gravity.RIGHT`, top/right margins 2dp — an intentional corner badge on the 40dp wrap, untouched by Fix 1.

## Check 4 — setStripExpanded + overlap: SOUND ✓

- `setStripExpanded` (**L2750–2755**): `stripCollapsedRow.visibility = if (expanded) GONE else VISIBLE`; `stripExpandedScroll.visibility = if (expanded) VISIBLE else GONE` — strictly mutually exclusive, and it calls `keepControls()` so the auto-hide timer restarts on toggle. The WRAP height change does not affect this logic (visibility is independent of measured height).
- Overlap: `stripBox` lives inside `topChrome`, a vertical LinearLayout added to the root at `Gravity.TOP` with WRAP height (**L2557**); the strip sits directly under the 56dp top bar. The seek bar and transport live in `bottomChrome`, a separate root child anchored `Gravity.BOTTOM` (**L2918**). Expanded, topChrome totals ≈ 56 + ~79 = **~135dp** from the top; bottomChrome is ~109dp tall; on Sir's landscape phone (~360dp window height) that leaves ~115dp of clear video between them — the grown strip cannot reach the seek area. Both chromes also auto-hide together. No breaking overlap.

## Check 5 — APK spot-check (independent, aapt2/apksigner)

File: `GhostBladePlayer-v0.5.1.apk`, 6,386,459 bytes, SHA-256
`49b800326342b95ef1c8f32b729309b4388e3490444c0ca4f687c49784e280a6`.

- `aapt2 dump badging`: **versionCode 7 / versionName 0.5.1** ✓ (minSdk 24, targetSdk 34)
- `aapt2 dump permissions`: **exactly 7** — READ_MEDIA_VIDEO, READ_MEDIA_AUDIO, READ_EXTERNAL_STORAGE (maxSdk 32), MODIFY_AUDIO_SETTINGS, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK, POST_NOTIFICATIONS. **No INTERNET** ✓
- Binary manifest (`aapt2 dump xmltree … --file AndroidManifest.xml`): `windowLayoutInDisplayCutoutMode = 1` (shortEdges) ✓
- `apksigner verify --print-certs`: signer cert SHA-256 **e38da55a054e6a24045121db1a7a69957c13db68911ec8abb94f2a15d2b193eb** ✓ — installs over v0.5.

## P3 notes (informational, not defects)

1. The pre-fix (v0.5) strip code is no longer in the tree, so the "was
   40dp + margins / was 30dp + margins / was 50dp" half of each fix rests on
   the in-code fix comments and the brief, not on a re-readable old source.
   The current (fixed) state is fully verified above, and the described old
   geometry is exactly what Sir's screenshots show.
2. MxIcon draws on a 24-unit grid scaled to its view box, so glyph centring
   follows the box centring verified here; on-device rendering remains
   Sir's test, as with every build (no runtime on the VM).

## Verdict: **PASS**

All three fixes verified in source with correct layout maths; "1X" and the
red dot untouched; expand/collapse logic and chrome geometry sound; no
other instance of the defect pattern anywhere in the app source; the APK
matches every claimed fact (vc 7 / 0.5.1, 7 permissions, no INTERNET,
cert e38da55a…, cutout shortEdges).
