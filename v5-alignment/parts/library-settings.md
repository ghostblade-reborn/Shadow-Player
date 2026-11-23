# GhostBlade Player v0.5 — Alignment & Icon Fidelity: NON-PLAYER Surfaces (Worker 2)

Scope: library, toolbar/app bar, drawer, menus, action mode, settings, music, secondary scenes
(Recycle Bin, Private Folder, History, Playlist Detail, USB, Media Cleaner), music widgets.
Method: static measurement only. MX values from decoded MX Player 2.12.4 (apktool) under
`~/workspace/projects/mxplayer-analysis/decoded/res/`; our values from programmatic code
(all layouts built with `dp()` calls — there is **no XML layout** for these surfaces except
the four music-widget layouts in `res/layout/`).

**Correction to the brief:** there is **no `Icons.kt`** in
`~/workspace/projects/ghostblade-player/src/com/ghostblade/player/`. Icons on these surfaces
come from (a) three custom `Drawable.draw()` implementations in `MainActivity.kt`
(`dotsDrawable` L127–149, `selectedDrawable` L151–180, `folderMiniDrawable` L182–211),
(b) Unicode text glyphs in `TextView`s (`▶ ⌕ ⋮ ← ✕ ♥ ▶`), (c) one vector asset
`res/drawable/ic_folder.xml`. **No `MxIcon` kind is reused on any non-player surface** —
grep for `MxIcon` in MainActivity.kt / SettingsActivity.kt / Surfaces.kt / Widgets.kt /
Library.kt returns zero hits. Worker 1's MxIcon geometry therefore has no overlap here.

Conventions: MX cite = decoded file + attribute/dimen/style name. Our cite = file + line.
Dimen scale entries verified in `res/values/dimens.xml`: `dp2/dp4/dp8/dp12/dp16/dp24/dp38/
dp40/dp48/dp56/dp64` = face value; `dp56_un_sw` = 56dp; `directory_width` = 62dp;
`sp10/sp12/sp14/sp16/sp18` = face value. Text appearances resolve through
`res/values/styles.xml`: `NewText.Medium.Primary` = 14sp semibold (font_muli_semibold),
`NewText.Primary` = 15sp regular, `NewText.Secondary` = 12sp regular,
`NewText.Medium.Tag` / `NewText.Medium.Box` = 10sp semibold, `NewText.Medium.Badge` = 11sp.

---

## 1. ALIGNMENT TABLES

### 1.1 Library — video list rows (`list_row_media.xml` vs `MainActivity.kt videoRow/thumbFrame`)

| Element | MX value (cite) | Our value (cite) | Delta | Fix value |
|---|---|---|---|---|
| Thumb frame | 96×56dp literal (`list_row_media.xml` `@id/icon_frame` + `@id/thumb` 96.0dip×56.0dip, centerCrop) | 96×56dp (MainActivity.kt L1752, `LinearLayout.LayoutParams(dp(96), dp(56))`) | 0 | — |
| Thumb card | CardView cornerRadius 4dp, elevation 2dp, `cardUseCompatPadding=true` (`list_row_media.xml` `@id/card`) | GradientDrawable corner 4dp, black fill, `clipToOutline` (L1638–1645); **no elevation/shadow, no compat padding** | shadow + compat inset missing; compat padding in MX insets content by ≈2dp on all sides | Add 2dp elevation-equivalent shadow + 2dp inset |
| Row container padding | L14 / T8 / B8, **no right padding** (`list_row_media.xml` `origin_ui_container` paddingLeft 14.0dip, paddingTop/Bottom 8.0dip) | L14 / T8 / R0 / B8 (L1748) | 0 | — |
| Row min height | `minHeight="54.0sp"` on root (`list_row_media.xml`) — note MX literally uses **sp** units here | none (height = max(56dp thumb, text block) + 16dp padding = ≥72dp) | not binding in either (content taller) | — |
| Centre type icon | 32×32dp, `fitXY`, centred (`@id/icon`) | "▶" **text glyph** 20sp in a 32×32dp TextView, centred (L1647–1655) | glyph is a font triangle, not the file-type icon; optical size ≈11×13dp vs 32dp icon box filled by MX's type raster | Draw type icon at 32×32dp |
| Title | 14sp semibold, maxLines 3, lineSpacingExtra 4dp, marginBottom 2dp (`@id/title`, `?gridFilePrimaryTextAppearance` → NewText.Medium.Primary) | 14sp sans-serif-medium, maxLines 3, lineSpacing 4dp, paddingBottom 2dp (L1756–1765) | 0 (weight via medium vs semibold — font-dependent, see §5) | — |
| Last-played line | 12sp regular, 1 line (`@id/tv_last_played` → NewText.Secondary) | 12sp, 1 line, only when played (L1768–1778) | 0 | — |
| Info row | 12×12dp folder-type icon (`@id/iv_folder_type`, `@dimen/dp12`) + 12sp text marginStart 2dp, maxLines 2; row marginBottom 4dp (`@id/rl_info`) | 12×12dp `folderMiniDrawable` + 12sp text paddingStart 2dp, maxLines 2; paddingBottom 4dp (L1781–1800) | 0 in geometry; glyph geometry differs (§2) | — |
| File-info row | FlexboxLayout of individual tag chips (`@id/file_info`) | Single concatenated " · "-joined 12sp line + separate accent status line (L1584–1607, L1802–1810) | chips vs plain line — visual rhythm differs | Render per-field chips |
| More button view | 40×38dp, padding T10/R22/B10 (L0), centred vertically (`@id/more`) | 40×38dp, padding (0,10,22,10) (L1811–1812) | 0 | — |
| More glyph | `ic_more_option` raster, intrinsic **18×18dp** (ICON-CATALOGUE: 27/36/54px) fitted into the 18×18dp content box (40−22 wide, 38−20 tall) | `dotsDrawable` fills content box: dots r = 0.11×18 ≈ **1.98dp**, step 0.33×18 ≈ **5.94dp** (L132–141) | container exact; dot proportions unverifiable vs raster (§5) | — |
| NEW tag | 10sp semibold white on `round_box_small` (2dp corners), margins L4/T4 (`@id/new_title`) | 10sp medium, tagBg 2dp corners, padding L/R 4dp, margins 4/4 (L1669–1680) | 0 | — |
| Duration tag | 10sp semibold white, `round_box_small`, tint `duration_background` (#80171717), margins R4/B4 (`@id/duration`) | 10sp medium, tagBg tint #80171717, padding L/R 4dp, margins R4/B4 (L1696–1706) | 0 vs decoded — **but contradicted by device evidence §3.2** | See §3.2 |
| Subtitle tag | 10sp semibold, `round_box_small`, bottom, left of duration (`@id/subtitle`) | **Never drawn** — model flag `hasEmbeddedSubtitle` is always false (FileOps.kt L255; comment MainActivity.kt L1629–1631) | **Missing element** | Wire subtitle detection; draw tag |
| Favourite mark | 16×16dp, margins T2/E2, `ic_more_added_to_favourites` intrinsic 24dp fitted (`@id/iv_favourite`) | "♥" **text glyph** 14sp in 16×16dp box, margins T2/E2 (L1682–1692) | glyph ≈10dp wide font heart vs 16dp fitted raster | Draw heart path in 16×16dp |
| Selected mark | 24×24dp, offset L68/T4 inside 96dp frame (= 4dp from end) (`@id/iv_selected`, `ic_file_selected` 24dp) | 24×24dp, gravity TOP\|END, margins T4/R4 (L1720–1726) | 0 in placement | — |
| Resume progress bar | **No equivalent element in MX layout** | 2dp horizontal ProgressBar at thumb bottom, MX-blue tint (L1708–1718) | Our-only element (retained feature, noted) | Keep (documented divergence) |

### 1.2 Library — folder/group rows (`list_row_listable.xml` vs `folderRow`)

| Element | MX value (cite) | Our value (cite) | Delta | Fix value |
|---|---|---|---|---|
| Folder icon view | 62×62dp (`@dimen/directory_width`), marginLeft 16dp (`list_row_listable.xml` `@id/icon`) | 62×62dp, marginLeft 16dp (L1471–1472, L1493–1495) | 0 (view box) — **glyph fill differs, §2; device evidence §3.1 supersedes** | See §3.1 |
| Text block margins | L16 / R16 from icon/end | padding L16 / R16 (L1498) | 0 | — |
| Title | 15sp regular, maxLines 2, lineSpacingExtra 4dp, marginBottom 2dp (`?gridFolderPrimaryTextAppearance` → NewText.Primary) | 15sp, maxLines 2, lineSpacing 4dp, paddingBottom 2dp (L1500–1508) | 0 | — |
| Count line | 12sp regular (`@id/count` → NewText.Secondary) | 12sp (L1519–1524) + wording "**N Files**" | size 0; **wording differs from device MX ("N videos"), §3.1** | "N videos" / "1 video" |
| Size tag beside count | 10sp semibold round-box, marginLeft 4dp (`@id/info_opposite`) | 10sp medium, tagBg, padding L/R 4dp, marginStart 4dp (L1525–1540) | 0 | — |
| Info line | 12sp, 1 line, marginTop 2dp (`@id/info_normal`) | 12sp, 1 line, paddingTop 2dp — but content is folder **path** / "▶ last played" (L1542–1552) | geometry 0; MX device rows show no third line (§3.1) | Suppress in folders root view |
| Count badge | `red_badge` bg, height **20dp**, padding L/R 3dp, 11sp semibold white (NewText.Medium.Badge), constrained Start_toEndOf + End_toEndOf `@id/icon` → **centred on the icon's right edge**, marginTop 6dp (`@id/badge`) | **No count badge exists.** Only a 10dp red **dot** (no number), gravity TOP\|END fully inside the icon box, marginTop 11dp, shown on NEW-tag condition (L1480–1492) | **Badge missing; dot Ø10 vs badge Ø≈20–21; dot centre inset ≈5dp from corner vs badge centre ON the corner** | Add numbered badge: min Ø20dp, centre on thumb top-right corner |
| New dot | 10×10dp `red_point`, marginTop 11dp, same straddle constraints (`@id/iv_new`) | (our dot above occupies this role) | Our dot matches `iv_new` size but not its straddle position | Offset dot centre onto icon corner |

### 1.3 Library — grid (`list_grid_media.xml`, `list_grid_listable.xml` vs `gridCell/folderGridCell`)

| Element | MX value (cite) | Our value (cite) | Delta | Fix value |
|---|---|---|---|---|
| Video cell root padding | T4 / B4 only (`list_grid_media.xml` root) | T4 / B4 (L1832) | 0 | — |
| Video card ratio | `layout_constraintDimensionRatio="1:0.564"`, corner 4dp, elevation 2dp | RatioFrameLayout 0.564 (L1610–1619), corner 4dp, no elevation | elevation missing | Add shadow |
| Centre type icon (grid) | 40×40dp fitXY | "▶" text 26sp in 40×40dp box (L1649) | as §1.1 | Draw icon 40×40dp |
| Grid title | 14sp semibold, marginTop 4dp, marginEnd 18dp, maxLines 2, **minLines 2**, lineSpacingExtra 4dp | identical values (L1845–1860) | 0 | — |
| Grid more button | 38×28dp, padding L20/B10, marginTop 2dp, `ic_grid_more` 18×18dp | 38×28dp, padding (20,0,0,10), marginTop 2dp, horizontal dots (L1857–1865) | 0 in geometry | — |
| Favourite (grid) | 24×24dp slot, margins T2/E2 | "♥" text 20sp in 24×24dp (L1684–1686) | glyph-only diff | Draw path |
| Folder grid icon | 62×62dp, margins L8/R8, centred (`list_grid_listable.xml`) | 62×62dp, margins L8/R8 (L1560–1562) | 0 (box) | — |
| Folder grid title/count | title 15sp centred maxLines 2, margins L8/R8; count 12sp centred, marginTop 2dp, marginBottom 8dp | identical (L1565–1580) | 0 | — |
| Grid container | RecyclerView; **column count / spacing / edge padding are set in adapter code — not in any decoded layout** | GridView AUTO_FIT, columnWidth 160dp, spacing H16/V16, padding 16 all sides (L359–365) | **MX unmeasurable from layouts (§5)** | — |

### 1.4 Toolbar / app bar (`toolbar.xml`, `activity_local_music_list.xml`, styles)

| Element | MX value (cite) | Our value (cite) | Delta | Fix value |
|---|---|---|---|---|
| Bar height | `?actionBarSize` = `@dimen/dp56_un_sw` = **56dp** (styles.xml L398 `actionBarSize`; `toolbar.xml`) | 56dp (MainActivity.kt L280; SettingsActivity.kt L341; Surfaces.kt `surfaceChrome` L241) | 0 | — |
| Title | **18sp**, font_muli_bold, maxLines 1, ellipsize end (`toolbar.xml` `@id/tv_title`, `@dimen/sp18`) | 18sp framework bold, maxLines 1, ellipsize end, paddingStart 8dp (MainActivity.kt L262–272); Settings/Surfaces titles 18sp **sans-serif-medium** (SettingsActivity.kt L334; Surfaces.kt L235) | size 0; Settings/Surfaces weight medium vs bold | Bold in Settings/Surfaces chrome |
| Root title text | Device: "**Folders**" (§3.1) | "**GhostBlade Player**" in folders view (updateChrome L665–673) | **Wrong root title** | "Folders" |
| Action buttons | AppCompat action items: min **48×48dp** (`abc_action_button_min_width_material`/`min_height_material` = 48dp, dimens.xml), icon **24dp** intrinsic (menu icons §2) | `barTextButton`: **Unicode text glyphs** 20sp, padding H10/V8 → ≈34–40dp wide × ≈42dp high, **width varies per glyph** (MainActivity.kt L236–244, used L274–278) | **Buttons ≈8–14dp narrower, ≈6dp shorter than 48×48; glyph ≈14dp vs 24dp icon; uneven widths → uneven spacing** | Fixed 48×48dp buttons, 24dp icons |
| Bar actions (root) | Device: **All / Search / View-toggle** (§3.1); menu/list.xml order: play_last (icon, always), grid toggle (icon, always), search (icon, always), overflow | **Play-last "▶" / Search "⌕" / Overflow "⋮"** text buttons; **no All button, no view-toggle button** (toggle buried in overflow, `showOverflow` M_LAYOUT) | **Composition differs at a glance** | All + Search + View-toggle icon buttons |
| Bar padding | Toolbar default content inset 16dp start (AppCompat); music toolbar padding 0 + contentInset 0 (`activity_local_music_list.xml`) | Bar padding L4/R4 (L256) | Title starts ≈12dp closer to edge than AppCompat default; device shot title inset ≈16dp (§3.1) | paddingStart 16dp net |
| Search bar | SearchView in 56dp bar (`list_search_mode.xml`: single SearchView action view) | Full custom bar 56dp, back "←" text btn + 16sp EditText + "✕" text btn (L286–330) | Height 0; affordances are text glyphs vs icons | Icon buttons 48×48dp |
| Selection / action mode | Action mode bar = `?actionBarSize` 56dp (abc_action_mode_bar + Theme.MX `windowActionModeOverlay`); close item = abc_action_mode_close_item_material (24dp close icon in 48dp-class button); menu/list_action_mode.xml icons: play/share/private/property as bar icons | Custom black bar 56dp (L319–352): title **15sp** padding L14/R6, actions as **13sp text labels** padding H12/V14 in a horizontal scroller, close = "✕" text button | Height 0; **MX shows icon buttons, ours shows scrolling text labels** — different width rhythm, labels ≈30–90dp wide each | 48×48dp icon buttons per list_action_mode.xml |
| Music toolbar | 56dp (`dp56_un_sw`), title 18sp semibold marginStart 4dp, more button **44×44dp container, padding 10dp, icon 24×24dp** (`local_music_toolbar_view.xml` `@id/layout_more`/`@id/iv_menu_more`) | Music uses generic `surfaceChrome` (56dp, 18sp medium, back text glyph) (Surfaces.kt L763) | More-button container/icon absent; weight diff | 44×44dp more btn, 24dp icon |

### 1.5 Drawer / profile navigation

MX's local library has **no navigation drawer** in `activity_media_list.xml` (no DrawerLayout).
The drawer-style surface is the profile ("Me") list in
`layout_drawerlayout_content_total.xml` + item layouts. Our app has **no drawer and no
profile surface at all** — the equivalent destinations live in the library overflow
PopupMenu (MainActivity.kt `showOverflow` L1100–1148). Device evidence (§3.3) shows MX
actually navigates with a **4-tab bottom bar**, which we also lack.

| Element | MX value (cite) | Our value (cite) | Delta | Fix value |
|---|---|---|---|---|
| Drawer row height | **48dp** (`drawer_item_local_music.xml` `@dimen/dp48`; `drawer_item_private_folder.xml` `@dimen/dp_48`; content_total rows `@dimen/dp48`) | n/a (no drawer rows) | Surface absent | — |
| Drawer row icon | drawableLeft on text, drawablePadding **12dp** (local music) / **22dp** (private folder, paddingLeft 16dp on text) (`drawer_item_*.xml`, style `?mxProfileItemText`) | n/a | Surface absent | — |
| Drawer icon size | `ic_local_music_navigation__light` intrinsic **20×20dp**; `ic_private_folder` **24×24dp** (ICON-CATALOGUE) | n/a | — | — |
| Drawer container margins | Body cards marginLeft/Right 8dp; first row marginTop 4dp (`layout_drawerlayout_content_total.xml`) | n/a | — | — |
| Drawer width | **Not measurable**: no DrawerLayout width/dimen in decoded layouts (`design_navigation_max_width` 280dp exists in dimens.xml but no layout in scope references it for this surface) | n/a | Unmeasurable (§5) | — |

### 1.6 Menus — library overflow, action mode, popup geometry

MX menus are framework AppCompat menus; geometry comes from
`abc_popup_menu_item_layout.xml` / `abc_list_menu_item_layout.xml` + dimens/styles.
Ours are framework `PopupMenu` (library overflow, MainActivity.kt L1100) and
`AlertDialog.setItems` lists (item menu L908–948, selection actions as bar text labels §1.4) —
framework geometry is inherited on both sides where the same widget is used.

| Element | MX value (cite) | Our value (cite) | Delta | Fix value |
|---|---|---|---|---|
| Popup min width | **196dp** (`abc_popup_menu_item_layout.xml` minWidth 196.0dip) | Framework PopupMenu (same abc layout family) | 0 (inherited) | — |
| Popup row height | `?dropdownListPreferredItemHeight` → 48dp class (abc_list_item_height_small_material = 48dp) | Framework-inherited | 0 | — |
| Popup item text | `?textAppearanceLargePopupMenu` → TextAppearance TitleMedium→ **16sp class** (styles.xml attr chain) | Framework-inherited for overflow; **AlertDialog item lists** (item menu) use dialog list appearance instead | Item-menu rows render with dialog styling, not popup styling | Use PopupMenu for item menu |
| Popup item icons | **None** in abc popup item layout (title + shortcut + submenu arrow only); icons appear only as **bar** action items | Our overflow adds no icons (framework PopupMenu icons not shown by default) | 0 | — |
| Library overflow content/order | menu/list.xml: Refresh(2, never), View▸(All folders/Folders/Files radio group, Sort by, Fields), Select(7), Settings(8), Help▸(9), Quit(11); bar: Play-last, Grid-toggle, Search | showOverflow L1100–1148: Refresh?, Layout toggle, View Menu, Equalizer, View▸(3 radio + Sort + Fields), Select, Playlists, Private Folder, Recycle Bin, History, Music, USB, Media Cleaner, Settings, Help▸(4 items), Quit? | Extra surface shortcuts (ours, by design); **Help submenu missing Version check / Bug report / About split** (ours: What's New/Features/FAQ/About only) | Note only — content parity is Worker-scope-external |
| Action-mode menu | menu/list_action_mode.xml: Play (icon, always), Share (icon, ifRoom), Private folder (icon, ifRoom), MX Share (CUT), Property (icon, always); rest overflow text | Selection bar = 7 **text labels**: "Play selected items, Play using…, Share, Lock in Private Folder, Hide, Rebuild thumbnails, Properties" (SELECTION_ACTIONS L1920–1922) | **Icons vs text labels**; ours scrolls horizontally | Icon buttons for Play/Share/Private/Property |
| Search-mode menu | Single SearchView item (menu/list_search_mode.xml) | Custom search bar (§1.4) | See §1.4 | — |
| Recycle-bin menu | menu/menu_recycle_bin.xml: View options (icon, ifRoom), Help (icon, ifRoom), Select (never), Delete All (never) — in toolbar/overflow | Text-label strip under bar: "View Menu · Help · Select · Delete All", 13sp, padding H14/V10 (Surfaces.kt L289–300) | **Strip ≈40dp tall text row vs MX toolbar menu** | Move into toolbar overflow |
| History menu | menu/menu_history_edit.xml: Delete/Share/Private as **bar icons** (always, visible in edit mode) | Single "Clear history" text label 13sp, padding H14/V10 (Surfaces.kt L689–706) | Text strip vs icon actions | Toolbar icon actions |

### 1.7 Settings rows (`activity_pref.xml`, `appcompat_preference_holo.xml`, `divider_preference.xml` vs SettingsActivity.kt)

MX presentation chain: `activity_pref.xml` = 56dp toolbar (`app_bar_height_56_un_sw`) +
RecyclerView. `Theme.MX` → `Preference.AppCompat` → layout **`appcompat_preference_holo.xml`**.

| Element | MX value (cite) | Our value (cite) | Delta | Fix value |
|---|---|---|---|---|
| Row min height | `?listPreferredItemHeightSmall` = **48dp** (`appcompat_preference_holo.xml` minHeight; abc_list_item_height_small_material 48dp) | `minimumHeight = dp(64)` (SettingsActivity.kt L401) with comment claiming 72dp — **the comment's "72dip on the decoded target" is not supported by the decoded files** | **+16dp too tall** | 48dp min height |
| Row side padding | `?listPreferredItemPaddingLeft/Right` = **16dp** (abc_action_bar_content_inset_material chain) | padding L16/R16 (L402) | 0 | — |
| Row vertical padding | Text block paddingTop/Bottom **16dp** (`appcompat_preference_holo.xml` RelativeLayout) → two-line row ≈ 16+16+~36 ≈ 68dp natural height | padding T6/B6 (L402) + minHeight 64 | One-line rows: MX ≈48dp (min) vs ours 64dp; two-line rows converge ≈66–68dp | minHeight 48, text padding T16/B16 |
| Icon slot | 48×48dp ImageView, `centerInside`, marginRight 8dp (`appcompat_preference_item_padding_inner`), container minWidth 0 (`appcompat_preference_icon_minWidth` = 0px) — slot collapses when no icon | **No icon slot at all** — rows are text + widget only (rowShell L392–421) | MX settings in scope carry no per-row icons either (frag pref XMLs set none for most nodes) — geometry slot exists in MX, absent in ours | Add collapsible 48dp slot if icons are added |
| Title size | `?textAppearanceListItem` → TitleMedium = **16sp** | 16sp (L408) | 0 | — |
| Summary size | `?textAppearanceListItemSecondary` → BodyMedium = **14sp**, maxLines 10 | **13sp** (L414), no maxLines cap, paddingTop 2dp | **−1sp** | 14sp |
| Widget frame | paddingLeft **16dp**, gravity centre\|right (`appcompat_preference_holo.xml`) | Widget added directly to row, no left padding (Check L441–455; swatch L492) | −16dp gap before checkbox/switch | paddingStart 16dp on widget |
| Checkbox | Framework CheckBox, `preference_widget_checkbox.xml` (wrap_content, button = theme checkbox ≈20dp visual / 32dp-class touch via framework) | Framework CheckBox, accent-tinted, `isClickable=false` (L443–448) | 0 (framework both sides) | — |
| Switch | `SwitchCompat` wrap_content (`preference_widget_switch_compat.xml`) — framework track ≈52×32dp class | **We use CheckBox for all boolean prefs; no Switch anywhere** in SettingsActivity.kt | Widget type differs (visual size ≈52×32 vs ≈20dp box) | SwitchCompat-equivalent for Check nodes |
| Category header | `divider_preference.xml`: 1dp divider + title **14sp bold**, accent, **padding 12dp all sides** | 14sp, sans-serif-medium **BOLD flag set**, accent, padding 12dp all sides (L425–432) + an additional hairline divider after **every** row (divider() L387–390, showScreen L366) | Header 0; **ours adds dividers between all rows — MX preference list has none between rows** (divider only above category) | Remove per-row dividers |
| Screen-link affordance | Preference rows navigate via whole-row tap; no chevron in holo layout | "›" **text glyph 20sp** appended (L435) | Extra glyph MX doesn't draw | Remove or replace with 24dp chevron icon |
| Settings toolbar | 56dp, title from `?mxToolbar` style | 56dp bar, back = "←" text glyph 22sp padding (8,10,12,10), title 18sp medium (L314–341) | Height/title size 0; back is text glyph vs 24dp icon in 48dp button | 48×48dp back button, 24dp icon |
| Colour swatch | `color_preference_widget.xml` (widget frame) — size not in layout (drawn by preference code) | 28×28dp View (L492) | MX unmeasurable from layouts (§5) | — |

### 1.8 Music screens (`activity_local_music_list.xml`, `list_row_music.xml`, `local_music_toolbar_view.xml` vs Surfaces.kt MusicActivity)

| Element | MX value (cite) | Our value (cite) | Delta | Fix value |
|---|---|---|---|---|
| Toolbar | 56dp (`dp56_un_sw`), tab indicator **38dp** (`@dimen/dp38`, MagicIndicator, padding L/R 4dp) | Generic 56dp chrome + tab strip of text labels 12sp, padding H12/V10 → strip ≈ **37dp** (Surfaces.kt L783–802) | Height ≈0 by accident; MX tabs are indicator-style (text + underline indicator), ours are plain accent text labels in a card-coloured strip | 38dp tab bar with indicator |
| Tab set | Tracks/Albums/Artists/Folders (+ Playlists/Favourites/Recent as activities) | Tracks, Playlists, Albums, Artists, Folders, Favourites, Recent — 7 text tabs in one non-scrolling row (L786) | 7×~60dp labels ≈ 420dp > 393dp screen → **tabs crowd/wrap risk; MX keeps 4 tabs + separate screens** | 4 tabs; move rest to menu |
| Music list row height | **64dp fixed** (`list_row_music.xml` height `@dimen/dp64`), padding T8/B8 | `surfaceRow`: padding T12/B12 + 16sp title + 2dp + 13sp summary ≈ **66–68dp**, not fixed (Surfaces.kt L243–262) | ≈+3dp, and height varies with content vs MX fixed 64dp | Fixed 64dp rows |
| Music row icon | **40×40dp**, marginLeft 16dp (`@id/icon`, `@dimen/dp40`), folder/album art | **No icon at all** — surfaceRow is text-only (Surfaces.kt L243–262) | **Missing 40dp icon + 16dp inset on every music row** | Add 40×40dp icon at marginStart 16dp |
| Music row title | 1 line, lineSpacingExtra 4dp, Mulish regular (size from theme feed style — not literal in layout; ≈16sp class) | 16sp, unlimited lines (surfaceRow) | maxLines missing | maxLines 1, ellipsize |
| Music row subtitle | **10sp** (`@dimen/sp10`), letterSpacing 0.04 (`@id/count`) | 13sp summary (artist · album · duration) | **+3sp too big** | 10sp |
| Music row text inset | Text block marginStart 8dp from icon, marginEnd 46dp | Text starts at row padding 16dp (no icon to offset from) | Text starts 32dp further left than MX (16 margin + 40 icon + 8 gap = 64dp MX text start vs 16dp ours) | Text start 64dp |
| Music more button | **40×56dp** view, padding T16/R16/B16 → glyph box 24×24dp, `mxskin__ic_detail_music_option` intrinsic **24dp** (`@id/iv_music_option`, `@dimen/dp_40`/`dp56`) | **None** — track actions only via long-press AlertDialog (Surfaces.kt L912–935) | **Missing per-row more button** | 40×56dp button, 24dp glyph |
| Music row checkbox | CheckBox with `mxskin__check_box_big` button (raster 24dp), marginRight 16dp, shown in select mode (`@id/check_box`) | None (no select mode in MusicActivity) | Missing select affordance | 24dp checkbox, marginEnd 16dp |
| Music action-mode strip | 38dp bar (`cl_action_mode` `@dimen/dp38`): close icon 24×24dp marginLeft 16dp, title 18sp semibold marginLeft 20dp, select-all checkbox marginRight 16dp | None | Missing | Per MX values |
| Empty state | `layout_empty_music.xml`: 84×84dp image (`@dimen/dp84`), title 14sp marginTop 24dp, action chip 36dp high, text 13sp bold, drawablePadding 6dp | Text row "No music found / Audio files…" (surfaceRow) | **No 84dp empty image, no chip** | Per MX values |

### 1.9 Secondary scenes

All our secondary scenes share `surfaceChrome` (56dp bar, back text glyph, 18sp medium
title) + `surfaceRow` (padding H16/V12, title 16sp, summary 13sp, gap 2dp) —
Surfaces.kt L211–262. MX's equivalents reuse the library row layouts or preference rows,
so almost every secondary row in our app is a **text-only row where MX uses the
96×56dp-thumb media row**.

| Scene / element | MX value (cite) | Our value (cite) | Delta | Fix value |
|---|---|---|---|---|
| Recycle Bin toolbar | `?actionBarSize` 56dp (`activity_recycle_bin.xml`) | 56dp (surfaceChrome) | 0 | — |
| Recycle Bin tips strip | 11sp text, padding T6/B6, margins L16/R16/T8, start icon + drawablePadding 6dp (`@id/tv_tips`, `@dimen/sp11`) | **Absent** | Missing element | Add per MX values |
| Recycle Bin rows | RecyclerView reusing media-row family (item layouts under recycle bin use 96×56dp thumb class; cf. `media_manager_video_item.xml`: thumb 96×56dp radius 4dp, title 14sp marginStart 8dp marginTop 1dp, size 11sp marginTop 3dp, more btn **30×30dp** padding H6/B7, glyph `ic_grid_more` 18dp) | Text-only surfaceRow 16sp/13sp (Surfaces.kt L341–397) | **No thumbs, title +2sp, no more button** | Media rows per media_manager_video_item |
| Private Folder rows | **`item_private_folder_row.xml` = list_row_media clone**: paddingStart 14dp, T8/B8, thumb 96×56dp, centre icon 32dp, card elevation **0dp** (vs 2dp library), NEW/subtitle/duration tags identical | Text-only surfaceRow; selection via "☑/☐" **text prefixes** in the title string (Surfaces.kt L559–617) | **Entire row geometry differs**; checkbox glyphs are font characters inside text, not a 24dp control | Reuse library videoRow; 24dp selected mark |
| Private Folder grid mode | `item_private_folder_grid.xml` (grid media clone) | Two surfaceRows side-by-side in a LinearLayout pair (L586–610) | Not a grid of media cells at all | Reuse gridCell |
| Private Folder toolbar actions | `layout_private_folder_tool_bar.xml` family (icon toolbar) | Text strip "List view · Refresh · Change your PIN · Select", 13sp, padding H14/V10 (L445–456) | Text strip vs toolbar actions | Toolbar/menu actions |
| History rows | No dedicated MX history-row layout in decode (history reuses media list); menu = §1.6 icon actions | Text-only surfaceRow (L715–753) | As Recycle Bin | Media rows |
| Playlist Detail rows | `activity_video_playlist_detail.xml` + media-row family; `item_play_list_video.xml` is the **online** playlist card (ratio 104:156) — not the local row | Text-only surfaceRow (L1015–1037); header bar = text strip "Sort by · More" 13sp (L991–1005) | No thumbs; text strip vs MX toolbar actions | Media rows |
| USB rows | `list_row_usb_media.xml` = media-row clone: padding L14/T8/R16/B8, thumb 96×56dp, centre icon 32dp; `list_row_usb_listable.xml` = folder-row clone (62dp icon); toolbar menu = Refresh icon only (menu/list_usb.xml) | Text-only surfaceRow for volumes **and** files (L1295–1344) | **No thumbs/folder icons anywhere in USB scene** | Clone media/folder rows |
| Media Cleaner rows | `activity_media_manager_cleaner.xml` + `media_manager_clean_video_item.xml` / `media_manager_video_item.xml`: thumb 96×56dp, title 14sp, size 11sp, date chip 10sp padding H4/V2, more 30×30dp (above) | Text-only surfaceRow + "☑/☐" text-prefix selection; section headers 13sp medium accent, padding (16,20,16,6) (L1548–1575) | No thumbs; title +2sp; summary +2sp (13 vs 11) | Media-manager rows per MX values |
| Media Cleaner header summary | Cleaner layouts use the same item family (no 16sp summary card) | surfaceRow summary card (L1577–1585) | Extra element | Note only |
| Empty states (generic) | `layout_empty_music.xml` pattern: 84dp image + 14sp title + 24dp gap (§1.8) | All empty states are a single surfaceRow text pair | No image, no spacing rhythm | Per MX pattern |

### 1.10 Music widgets (`widget_music_player_*.xml` vs our `res/layout/widget_music_*.xml`)

MX widget layouts exist in the decode (`widget_music_player_single_row.xml`,
`_double_row_long/short`, `_square_big`, `_square_small`, rotate variants) — these are
RemoteViews layouts, directly comparable to ours.

| Element | MX value (cite) | Our value (cite) | Delta | Fix value |
|---|---|---|---|---|
| Single-row: artwork/disk | Disk **68×68dp**, marginLeft 10dp, centred vertically; inner disk view **42×42dp**, marginLeft 23dp (`widget_music_player_single_row.xml` `@id/bg_disk`/`@id/disk_view`) | Launcher icon **40×40dp** (widget_music_single_row.xml) | **−28dp artwork; no disk motif** | 68dp disk, 42dp inner |
| Single-row: title | **14sp semibold**, marginLeft 8dp, marginTop 12dp, marginRight 16dp, lineSpacingExtra 6sp, letterSpacing −0.01 | 14sp regular, marginStart 10dp | size 0; weight/spacing differ | Semibold, margins 8/12/16 |
| Single-row: artist | **12sp** regular | 12sp | 0 | — |
| Single-row: transport buttons | **34×34dp** ImageViews, padding **5dp** → glyph 24dp; play marginRight 14dp, next marginRight 11dp, bottom margin 7dp; plus **favourite** button 34×34dp | Text glyphs "⏮ ▶ ⏭" 18/20/18sp, padding 8dp → button ≈34–36dp wide but **glyph is a font character**, no favourite button | Button size ≈0; glyph type + missing fav | 34×34dp image buttons, 24dp glyphs, add fav |
| Square-small | Centred disk **82×82dp**, inner **50×50dp**, no text/transport in the small square (`widget_music_player_square_small.xml`) | 36dp icon + 12sp title + 10sp artist + 15/18/15sp text transport, padding 8dp (widget_music_square_small.xml) | **Completely different composition** | Disk-only 82/50dp |
| Double-row | Root heights 108/140dp variants, disk 68dp, transport 34×34dp padding 5dp (`widget_music_player_double_row_long.xml`) | Padding 10dp, icon 44dp, title 15sp, artist 12sp, transport text 20/24/20sp padding 10dp | Title +1sp; icon −24dp; transport glyph type | Per MX values |
| Square-big | (same disk family; `_square_big` in decode) | Padding 14dp, icon 72dp, title 15sp, transport text 20/26/20sp | Icon size coincidentally ≈ MX disk class, but it's the app launcher icon, centred column vs MX disk composition | Per MX layout |

---

## 2. ICON GEOMETRY

### 2.1 Icons drawn OUTSIDE MxIcon (full geometry — all icons on these surfaces)

MX's icons on these surfaces are **all raster** (webp/png) except the folder asset class —
ICON-CATALOGUE flags every entry below RASTER-ONLY, so MX has **no vector viewport/pathData**
for them; geometry comparison is therefore intrinsic-size + container vs our drawn proportions.
The only MX vector drawable in scope checked, `ic_more_option.xml` / `ic_grid_more.xml` /
`ic_action_search.xml` / `ic_grid.xml` / `ic_action_play.xml` in decoded `res/drawable/`,
are **bitmap/selector wrappers, not vectors** (files contain no `<vector>` root).

| MX drawable | MX intrinsic (catalogue) | MX render size + container/padding | Our drawing / render | Geometry differences |
|---|---|---|---|---|
| `ic_more_option` (row ⋮) | 18×18dp (27/36/54px) | 18×18dp glyph in 40×38dp view, padding T10/R22/B10 (`list_row_media.xml`) | `dotsDrawable(vertical)` in identical 40×38dp view/padding (MainActivity.kt L127–149, L1811): content box 18×18dp; dots r≈1.98dp (0.11×box), centres at ±5.94dp (0.33×box) and centre; dot Ø≈3.96dp, gap between dot edges ≈1.98dp | Container exact. Dot Ø/spacing ratio (r=0.11, step=0.33 of box) is **our invention** — MX raster pixel geometry was not measured (§5). Optical centre: ours exact box centre |
| `ic_grid_more` (grid ⋮ / media-manager) | 18×18dp | Grid: 18×18dp in 38×28dp view, padding L20/B10 (`list_grid_media.xml`); Cleaner: 30×30dp view, padding H6/B7 → ≈18×18dp glyph (`media_manager_video_item.xml`) | `dotsDrawable(horizontal)` in identical 38×28dp view/padding (L1857–1865) | Same caveat as above |
| `ic_file_selected` | 24×24dp webp | 24×24dp slot: list row at L68/T4 of thumb; grid T4/R4; folder rows over icon T6 (`iv_selected` in list_row_media / list_grid_media / list_row_listable) | `selectedDrawable` (L151–180): disc fills **100%** of the 24dp box (r = w/2), white tick stroke = 0.09×w ≈ **2.16dp**, tick points (0.28,0.52)→(0.44,0.68)→(0.74,0.34) of box | Placement exact. Whether MX's raster disc fills the full 24dp or is inset with transparent margin is **not measurable** from catalogue data (§5); if MX's disc is inset, ours reads larger |
| `ic_folder_internal` (info-row mini folder) | **9.67×7.33dp** png (29×22px @xxhdpi) — aspect 1.32:1 | Rendered in 12×12dp ImageView (`@id/iv_folder_type`, default FIT_CENTER) → glyph ≈9.7×7.3dp centred, ≈2.3dp vertical transparent margin | `folderMiniDrawable` (L182–211) in 12×12dp: body spans x 0.04–0.96 (**92%** w = 11.0dp), y 0.22–0.78 (**56%** h = 6.7dp); tab notch to 0.42/0.54; front panel from y 0.46 | **Our glyph is ≈1.3dp wider and ≈0.6dp shorter than MX's**; our aspect 1.64:1 vs MX 1.32:1 — ours reads flatter/wider. Fix: scale body to 9.7×7.3dp centred in the 12dp box (x 0.10–0.90, y 0.20–0.80 of box at MX aspect) |
| `ic_statusbar_folder` (status bar) | **32×32dp** webp (square canvas) | 24×24dp view, marginLeft 18dp (`activity_media_list.xml` rl_status `@id/image`, `@dimen/dp24`) → raster scaled to 24dp | `folderMiniDrawable` in 24×24dp view, marginLeft 18dp (L394–396) | Container exact; glyph internal proportions as above (folderMini reused) |
| `mxskin__ic_default_folder__light` (folder rows) | **≈42.7×42.7dp** (128px @xxhdpi; xhdpi implies 48dp — densities disagree, catalogue) | 62×62dp ImageView, default FIT_CENTER (`list_row_listable.xml`) → glyph ≈42.7dp centred, ≈9.6dp transparent margin each side | `R.drawable.ic_folder` **vector**: viewport 48×48, declared 48dp; rendered in 62×62dp ImageView (L1471) — VectorDrawable scales to the view, so glyph body (path x 6–42, y 8–38 of 48) renders at **46.5×38.8dp with zero transparent margin** | **Our folder glyph is ≈+3.8dp wider and fills the box edge-to-edge vs MX's ≈42.7dp glyph floating with ≈9.6dp margins** — the single most visible icon-size difference in folder rows. Fix: pad the vector (or inset the ImageView) so the silhouette occupies ≈42.7dp centred in 62dp. **Device evidence modifies this — see §3.1: on Sir's phone the folder silhouette renders ≈70×53dp, i.e. the Aurora folders UI does not use this 62dp slot at all** |
| `ic_folder.xml` (ours, path geometry) | — (our asset) | viewport 48×48; body path `M6,10 … L19,8 L23,12 L40,12 … L42,36 L8,38 Z` fill #FFC107; front rect path y16–38 fill #FFECB3 (res/drawable/ic_folder.xml) | Tab: x 6–23 (35% w), front panel top at y16 (33% h), body bottom y38 (79% h), body width 75% of viewport | Silhouette proportions are a generic folder; no MX pathData exists to diff against (MX is raster) |
| `ic_action_search` / `ic_grid` (view toggle) / `ic_action_play` (play-last) | Search + grid: catalogue lists `ic_grid` twice — **24×24dp** (36/48/72px) and **20×20dp** (30/40/60px) entries conflict (§5); action icons generally 24dp class | Menu bar items in 48×48dp-min AppCompat buttons (menu/list.xml `showAsAction=always`) | **Not drawn at all** — replaced by "⌕" / "▶" 20sp text glyphs (L274–277); view-toggle has **no bar presence** | Missing icons; text glyphs ≈13–15dp optical vs 24dp icons |
| `ic_more_added_to_favourites` (♥ mark) | 24×24dp png | Fitted into 16×16dp (list) / 24×24dp (grid) slots, FIT_CENTER | "♥" text glyph 14sp / 20sp (§1.1) | Font heart: asymmetric, baseline-offset within box vs centred raster; ≈10dp (list) optical width vs 16dp fitted raster |
| `mxskin__ic_detail_music_option__light` (music ⋮) | 24×24dp | 24×24dp glyph in 40×56dp view, padding T16/R16/B16 (`list_row_music.xml`) | **Absent** (no music more button) | Missing |
| `mxskin__check_box_big__light` (music select checkbox) | checked/unchecked rasters 24×24dp | CheckBox wrap_content, marginRight 16dp (`list_row_music.xml`) | Absent in music; selection elsewhere = `selectedDrawable` overlay or "☑/☐" text prefixes (Surfaces.kt) | Missing / text-glyph substitute |
| Drawer icons `ic_local_music_navigation__light` / `ic_private_folder` | 20×20dp / 24×24dp | drawableLeft with drawablePadding 12dp / 22dp in 48dp rows | No drawer surface | — |
| Back / close icons | AppCompat `abc_ic_ab_back` / action-mode close: 24dp vectors in 48dp-class buttons | "←" 20sp text (MainActivity L259), "←" 22sp (SettingsActivity L328, Surfaces L228), "✕" 20sp text | Text arrows: stroke weight = font's, optical size ≈15dp vs 24dp icon; button ≈40×42dp vs 48×48dp | 48×48dp buttons, 24dp icons |
| FAB play-last | `list_layout_recyclerview.xml` play_last card 48×48dp | 48×48dp oval GradientDrawable with "▶" 20sp text centred (L418–432) | Size exact; glyph is text | Draw triangle path ≈24dp |

### 2.2 Reused MxIcon kinds on these surfaces

**None.** Verified by grep: `MxIcon` does not appear in MainActivity.kt, SettingsActivity.kt,
Surfaces.kt, Widgets.kt, Library.kt or Settings.kt. Every icon on the non-player surfaces is
one of the §2.1 items. (If a future build routes these surfaces through MxIcon, the render
sizes it would need are the "MX render size" column above: 18dp dots, 24dp bar icons,
24dp selected mark, 32/40dp centre type icons.)

---
