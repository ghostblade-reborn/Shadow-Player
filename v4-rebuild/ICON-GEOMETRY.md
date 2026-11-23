# ICON GEOMETRY — MX density truth for code-drawn icons (Foundation Team, Step 0)

Source: `mxplayer-analysis/mx-icons/ICON-CATALOGUE.md` (measured from MX Player 2.12.4 APK:
raster dp = px / density scale; vector dp = the vector's own android:width/height).
Code teams: draw each icon at the **Derived size** below, on the stated viewport where one
exists. Icons with full verbatim pathData already exist as `res/drawable/ic_mx_<name>.xml`
(24 files, see V4-WORKLOG Foundation notes) — use those instead of code-drawing them.
For raster-only icons there is NO vector geometry in the APK: match the derived dp size
and the per-density pixel sizes; do not invent a viewport.
Composite types (selector / level-list / layer-list / shape) are containers, not glyphs —
their geometry is the geometry of their frames/children in the catalogue entry.

## Player chrome (catalogue group: 146) — 146 entries catalogued below

| Catalogue name | Type | Derived size (dp) | Densities measured (folder: px) |
|---|---|---|---|
| `bg_auto_timer` | shape | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 740 B |
| `bg_player_menu` | shape | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 372 B |
| `bg_sleep_timer_shading` | shape | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 456 B |
| `bg_speed_menu` | shape | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 484 B |
| `bookmark_seek_bar_progress` | selector | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 1380 B |
| `brightbar_progress` | layer-list | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 1096 B |
| `exo_controls_fastforward` | vector + raster (webp) | 32.0dip × 32.0dip (vector android:width/height) | drawable-ldpi: 24×24px · 144 B · drawable-hdpi: 48×48px · 252 B · drawable-xhdpi: 64×64px · 256 B · drawable-xxhdpi: 96×96px · 428 B · drawable-anydpi: XML · 588 B |
| `exo_controls_next` | webp | 48 × 48 dp (from xxhdpi; densities disagree slightly: hdpi→48×48, ldpi→32×32, xhdpi→48×48, xxhdpi→48×48) | drawable-ldpi: 24×24px · 124 B · drawable-hdpi: 72×72px · 296 B · drawable-xhdpi: 96×96px · 358 B · drawable-xxhdpi: 144×144px · 520 B |
| `exo_controls_pause` | webp | 48 × 48 dp (from xxhdpi; densities disagree slightly: hdpi→48×48, ldpi→32×32, xhdpi→48×48, xxhdpi→48×48) | drawable-ldpi: 24×24px · 56 B · drawable-hdpi: 72×72px · 278 B · drawable-xhdpi: 96×96px · 352 B · drawable-xxhdpi: 144×144px · 524 B |
| `exo_controls_play` | webp | 48 × 48 dp (from xxhdpi; densities disagree slightly: hdpi→48×48, ldpi→32×32, xhdpi→48×48, xxhdpi→48×48) | drawable-ldpi: 24×24px · 132 B · drawable-hdpi: 72×72px · 398 B · drawable-xhdpi: 96×96px · 500 B · drawable-xxhdpi: 144×144px · 720 B |
| `exo_controls_previous` | webp | 48 × 48 dp (from xxhdpi; densities disagree slightly: hdpi→48×48, ldpi→32×32, xhdpi→48×48, xxhdpi→48×48) | drawable-ldpi: 24×24px · 120 B · drawable-hdpi: 72×72px · 294 B · drawable-xhdpi: 96×96px · 356 B · drawable-xxhdpi: 144×144px · 510 B |
| `exo_controls_replay` | webp | 48 × 48 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 72×72px · 702 B · drawable-xhdpi: 96×96px · 846 B · drawable-xxhdpi: 144×144px · 1214 B |
| `exo_controls_replay_small` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 412 B · drawable-xhdpi: 48×48px · 578 B · drawable-xxhdpi: 72×72px · 702 B |
| `exo_controls_rewind` | vector + raster (webp) | 32.0dip × 32.0dip (vector android:width/height) | drawable-ldpi: 24×24px · 170 B · drawable-hdpi: 48×48px · 242 B · drawable-xhdpi: 64×64px · 300 B · drawable-xxhdpi: 96×96px · 444 B · drawable-anydpi: XML · 600 B |
| `exo_ic_audio` | webp | 32 × 32 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 48×48px · 378 B · drawable-xhdpi: 64×64px · 316 B · drawable-xxhdpi: 96×96px · 330 B |
| `exo_ic_audio_white` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 262 B · drawable-xhdpi: 48×48px · 264 B · drawable-xxhdpi: 72×72px · 244 B |
| `exo_ic_speed_white` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 440 B · drawable-xhdpi: 48×48px · 580 B · drawable-xxhdpi: 72×72px · 686 B |
| `exo_ic_subtitle` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 274 B · drawable-xhdpi: 48×48px · 338 B · drawable-xxhdpi: 72×72px · 264 B |
| `exo_ic_subtitle_white` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 254 B · drawable-xhdpi: 48×48px · 294 B · drawable-xxhdpi: 72×72px · 316 B |
| `exo_ic_subtitle_white_red` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 426 B · drawable-xhdpi: 48×48px · 500 B · drawable-xxhdpi: 72×72px · 516 B |
| `exo_player_retry_bg` | selector | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 1076 B |
| `gesture_brightness` | webp | 120 × 100 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 180×150px · 1386 B · drawable-xhdpi: 240×200px · 2056 B · drawable-xxhdpi: 360×300px · 2710 B |
| `gesture_fast_speed` | webp | 167.333 × 33.3333 dp (consistent across xxhdpi) | drawable-xxhdpi: 502×100px · 2390 B |
| `gesture_forward` | webp | 120 × 100 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 180×150px · 1642 B · drawable-xhdpi: 240×200px · 2472 B · drawable-xxhdpi: 360×300px · 3420 B |
| `gesture_guide_brightness` | webp | 120 × 120 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 180×180px · 1490 B · drawable-xhdpi: 240×240px · 2168 B · drawable-xxhdpi: 360×360px · 2848 B |
| `gesture_guide_forward` | webp | 30 × 30 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 45×45px · 304 B · drawable-xhdpi: 60×60px · 398 B · drawable-xxhdpi: 90×90px · 400 B |
| `gesture_guide_rewind` | webp | 30 × 30 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 45×45px · 312 B · drawable-xhdpi: 60×60px · 408 B · drawable-xxhdpi: 90×90px · 396 B |
| `gesture_guide_seek` | webp | 120 × 120 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 180×180px · 1200 B · drawable-xhdpi: 240×240px · 1660 B · drawable-xxhdpi: 360×360px · 2374 B |
| `gesture_play_pause` | webp | 120 × 100 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 180×150px · 1064 B · drawable-xhdpi: 240×200px · 1592 B · drawable-xxhdpi: 360×300px · 2122 B |
| `gesture_rewind` | webp | 120 × 100 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 180×150px · 1628 B · drawable-xhdpi: 240×200px · 2486 B · drawable-xxhdpi: 360×300px · 3440 B |
| `gesture_speed` | webp | 120 × 100 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 180×150px · 2130 B · drawable-xhdpi: 240×200px · 3368 B · drawable-xxhdpi: 360×300px · 4576 B |
| `gesture_speed_ff` | webp | 120 × 100 dp (from xxhdpi; densities disagree slightly: hdpi→80×66.6667, xhdpi→120×100, xxhdpi→120×100) | drawable-hdpi: 120×100px · 1148 B · drawable-xhdpi: 240×200px · 2298 B · drawable-xxhdpi: 360×300px · 3488 B |
| `gesture_subtitle` | png | 240 × 66.6667 dp (from xxhdpi; densities disagree slightly: hdpi→240×66.6667, xhdpi→240×66.5, xxhdpi→240×66.6667) | drawable-hdpi: 360×100px · 13944 B · drawable-xhdpi: 480×133px · 24727 B · drawable-xxhdpi: 720×200px · 11662 B |
| `gesture_volume` | webp | 120 × 100 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 180×150px · 1318 B · drawable-xhdpi: 240×200px · 1924 B · drawable-xxhdpi: 360×300px · 2646 B |
| `ic_ai_smart_enhance` | vector | 20.0dip × 18.0dip (vector android:width/height) | drawable: XML · 3372 B |
| `ic_ai_subtitle_policy` | webp | 16 × 16 dp (consistent across xxhdpi) | drawable-xxhdpi: 48×48px · 398 B |
| `ic_aspect_ratio` | webp | 42 × 42 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 63×63px · 354 B · drawable-xhdpi: 84×84px · 414 B · drawable-xxhdpi: 126×126px · 586 B |
| `ic_audiotrack_dark` | png | 24 × 24 dp (consistent across hdpi, mdpi, xhdpi, xxhdpi) | drawable-mdpi: 24×24px · 146 B · drawable-hdpi: 36×36px · 205 B · drawable-xhdpi: 48×48px · 235 B · drawable-xxhdpi: 72×72px · 306 B |
| `ic_audiotrack_light` | png | 24 × 24 dp (consistent across hdpi, mdpi, xhdpi, xxhdpi) | drawable-mdpi: 24×24px · 145 B · drawable-hdpi: 36×36px · 203 B · drawable-xhdpi: 48×48px · 226 B · drawable-xxhdpi: 72×72px · 304 B |
| `ic_audiotrack_more` | webp | 42 × 42 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 63×63px · 388 B · drawable-xhdpi: 84×84px · 586 B · drawable-xxhdpi: 126×126px · 718 B |
| `ic_audiotrack_white_24dp` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 174 B · drawable-xhdpi: 48×48px · 180 B · drawable-xxhdpi: 72×72px · 248 B |
| `ic_audiotrack_white_24dp_new` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 222 B · drawable-xhdpi: 48×48px · 284 B · drawable-xxhdpi: 72×72px · 308 B |
| `ic_brightness` | webp | 21 × 21 dp (from xxhdpi; densities disagree slightly: hdpi→21.3333×21.3333, xhdpi→21×21, xxhdpi→21×21) | drawable-hdpi: 32×32px · 524 B · drawable-xhdpi: 42×42px · 476 B · drawable-xxhdpi: 63×63px · 578 B |
| `ic_button_backward` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 392 B · drawable-xhdpi: 48×48px · 556 B · drawable-xxhdpi: 72×72px · 764 B |
| `ic_button_decoder` | level-list | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 584 B |
| `ic_button_decoder_more` | level-list | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 584 B |
| `ic_button_forward` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 388 B · drawable-xhdpi: 48×48px · 532 B · drawable-xxhdpi: 72×72px · 738 B |
| `ic_button_lock_plus` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 358 B · drawable-xhdpi: 48×48px · 412 B · drawable-xxhdpi: 72×72px · 534 B |
| `ic_button_locks` | level-list | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 684 B |
| `ic_button_next` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 288 B · drawable-xhdpi: 48×48px · 350 B · drawable-xxhdpi: 72×72px · 454 B |
| `ic_button_pause` | webp | 33 × 32 dp (from xxhdpi; densities disagree slightly: hdpi→33.3333×32, xhdpi→33×32, xxhdpi→33×32) | drawable-hdpi: 50×48px · 260 B · drawable-xhdpi: 66×64px · 342 B · drawable-xxhdpi: 99×96px · 478 B |
| `ic_button_pip` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 310 B · drawable-xhdpi: 48×48px · 380 B · drawable-xxhdpi: 72×72px · 438 B |
| `ic_button_pip_more` | webp | 28 × 28 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 42×42px · 868 B · drawable-xhdpi: 56×56px · 1198 B · drawable-xxhdpi: 84×84px · 674 B |
| `ic_button_play` | webp | 32 × 32 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 48×48px · 330 B · drawable-xhdpi: 64×64px · 478 B · drawable-xxhdpi: 96×96px · 586 B |
| `ic_button_play_pause` | level-list | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 484 B |
| `ic_button_play_small` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 234 B · drawable-xhdpi: 48×48px · 274 B · drawable-xxhdpi: 72×72px · 332 B |
| `ic_button_prev` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 278 B · drawable-xhdpi: 48×48px · 338 B · drawable-xxhdpi: 72×72px · 470 B |
| `ic_button_rotation_locked` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 412 B · drawable-xhdpi: 48×48px · 482 B · drawable-xxhdpi: 72×72px · 710 B |
| `ic_button_rotation_unlocked` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 334 B · drawable-xhdpi: 48×48px · 418 B · drawable-xxhdpi: 72×72px · 610 B |
| `ic_button_unlock_plus` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 338 B · drawable-xhdpi: 48×48px · 422 B · drawable-xxhdpi: 72×72px · 516 B |
| `ic_button_zoom` | level-list | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 684 B |
| `ic_button_zoom_more` | level-list | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 784 B |
| `ic_crop_more` | webp | 28 × 28 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 42×42px · 550 B · drawable-xhdpi: 56×56px · 752 B · drawable-xxhdpi: 84×84px · 1144 B |
| `ic_crop_white_24dp` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 302 B · drawable-xhdpi: 48×48px · 350 B · drawable-xxhdpi: 72×72px · 528 B |
| `ic_lock` | vector | 24.0dip × 24.0dip (vector android:width/height) | drawable: XML · 812 B |
| `ic_lock_close_white` | webp | 24 × 24 dp (consistent across xhdpi) | drawable-xhdpi: 48×48px · 252 B |
| `ic_lock_open_white` | webp | 24 × 24 dp (consistent across xhdpi) | drawable-xhdpi: 48×48px · 258 B |
| `ic_lock_white_24dp` | webp | 24 × 24 dp (consistent across hdpi) | drawable-hdpi: 36×36px · 262 B |
| `ic_media_control_skip_next` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 382 B · drawable-xhdpi: 48×48px · 542 B · drawable-xxhdpi: 72×72px · 604 B |
| `ic_media_control_skip_previous` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 394 B · drawable-xhdpi: 48×48px · 536 B · drawable-xxhdpi: 72×72px · 598 B |
| `ic_menu_decoder_hardware` | webp | 26 × 26 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 39×39px · 340 B · drawable-xhdpi: 52×52px · 464 B · drawable-xxhdpi: 78×78px · 584 B |
| `ic_menu_decoder_hardware_more` | webp | 28 × 28 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 42×42px · 592 B · drawable-xhdpi: 56×56px · 764 B · drawable-xxhdpi: 84×84px · 1232 B |
| `ic_menu_decoder_hardware_plus` | webp | 26 × 26 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 39×39px · 376 B · drawable-xhdpi: 52×52px · 526 B · drawable-xxhdpi: 78×78px · 656 B |
| `ic_menu_decoder_hardware_plus_more` | webp | 28 × 28 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 42×42px · 664 B · drawable-xhdpi: 56×56px · 894 B · drawable-xxhdpi: 84×84px · 1412 B |
| `ic_menu_decoder_software` | webp | 26 × 26 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 39×39px · 404 B · drawable-xhdpi: 52×52px · 588 B · drawable-xxhdpi: 78×78px · 780 B |
| `ic_menu_decoder_software_more` | webp | 28 × 28 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 42×42px · 642 B · drawable-xhdpi: 56×56px · 890 B · drawable-xxhdpi: 84×84px · 1430 B |
| `ic_menu_subtitle_open` | webp | 24 × 24 dp (consistent across xxhdpi) | drawable-xxhdpi: 72×72px · 392 B |
| `ic_more_player_more` | webp | 42 × 42 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 63×63px · 292 B · drawable-xhdpi: 84×84px · 368 B · drawable-xxhdpi: 126×126px · 460 B |
| `ic_more_subtitle` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 320 B · drawable-xhdpi: 48×48px · 346 B · drawable-xxhdpi: 72×72px · 476 B |
| `ic_mx_mute_off` | webp | 48 × 48 dp (consistent across hdpi) | drawable-hdpi: 72×72px · 668 B |
| `ic_mx_screen_rotation` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 462 B · drawable-xhdpi: 48×48px · 572 B · drawable-xxhdpi: 72×72px · 932 B |
| `ic_pip` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 242 B · drawable-xhdpi: 48×48px · 224 B · drawable-xxhdpi: 72×72px · 302 B |
| `ic_pip_control` | webp | 20 × 20 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 30×30px · 318 B · drawable-xhdpi: 40×40px · 464 B · drawable-xxhdpi: 60×60px · 438 B |
| `ic_player_mute` | webp | 32 × 32 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 48×48px · 980 B · drawable-xhdpi: 64×64px · 1310 B · drawable-xxhdpi: 96×96px · 1830 B |
| `ic_player_scrubber` | shape | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 628 B |
| `ic_player_unmute` | webp | 32 × 32 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 48×48px · 834 B · drawable-xhdpi: 64×64px · 1088 B · drawable-xxhdpi: 96×96px · 1572 B |
| `ic_private_folder_mode_unlock` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 226 B · drawable-xhdpi: 48×48px · 280 B · drawable-xxhdpi: 72×72px · 382 B |
| `ic_ratio_unlock` | webp | 16 × 16 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 24×24px · 226 B · drawable-xhdpi: 32×32px · 278 B · drawable-xxhdpi: 48×48px · 318 B |
| `ic_reset_speed` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 526 B · drawable-xhdpi: 48×48px · 774 B · drawable-xxhdpi: 72×72px · 1042 B |
| `ic_share_player` | webp | 42 × 42 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 63×63px · 438 B · drawable-xhdpi: 84×84px · 648 B · drawable-xxhdpi: 126×126px · 846 B |
| `ic_smart_enhance` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 354 B · drawable-xhdpi: 48×48px · 498 B · drawable-xxhdpi: 72×72px · 498 B |
| `ic_smart_enhance_on` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 648 B · drawable-xhdpi: 48×48px · 814 B · drawable-xxhdpi: 72×72px · 1272 B |
| `ic_subtitle_audio` | webp | 20 × 19 dp (from xxhdpi; densities disagree slightly: hdpi→20×19.3333, xhdpi→20×19, xxhdpi→20×19) | drawable-hdpi: 30×29px · 220 B · drawable-xhdpi: 40×38px · 224 B · drawable-xxhdpi: 60×57px · 496 B |
| `ic_subtitle_more` | webp | 42 × 42 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 63×63px · 252 B · drawable-xhdpi: 84×84px · 290 B · drawable-xxhdpi: 126×126px · 376 B |
| `ic_unlock_white_24dp` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 310 B · drawable-xhdpi: 48×48px · 406 B · drawable-xxhdpi: 72×72px · 470 B |
| `ic_video_playlist_close` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 248 B · drawable-xhdpi: 48×48px · 264 B · drawable-xxhdpi: 72×72px · 386 B |
| `ic_video_playlist_drag` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 158 B · drawable-xhdpi: 48×48px · 150 B · drawable-xxhdpi: 72×72px · 174 B |
| `ic_video_playlist_navigation` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 238 B · drawable-xhdpi: 48×48px · 248 B · drawable-xxhdpi: 72×72px · 318 B |
| `ic_video_playlist_white` | webp | 25 × 24 dp (from xxhdpi; densities disagree slightly: hdpi→25.3333×24, xhdpi→25×24, xxhdpi→25×24) | drawable-hdpi: 38×36px · 268 B · drawable-xhdpi: 50×48px · 310 B · drawable-xxhdpi: 75×72px · 378 B |
| `ic_video_speed_play` | webp | 8 × 8 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 12×12px · 142 B · drawable-xhdpi: 16×16px · 176 B · drawable-xxhdpi: 24×24px · 208 B |
| `ic_vol_mute` | vector + raster (png) | 24.0dip × 24.0dip (vector android:width/height) | drawable-ldpi: 18×18px · 255 B · drawable-mdpi: 24×24px · 294 B · drawable-hdpi: 36×36px · 476 B · drawable-xhdpi: 48×48px · 555 B · drawable-xxhdpi: 72×72px · 768 B · drawable-xxxhdpi: 96×96px · 1001 B · drawable-anydpi: XML · 948 B |
| `ic_vol_unmute` | vector + raster (png) | 24.0dip × 24.0dip (vector android:width/height) | drawable-ldpi: 18×18px · 225 B · drawable-mdpi: 24×24px · 223 B · drawable-hdpi: 36×36px · 361 B · drawable-xhdpi: 48×48px · 410 B · drawable-xxhdpi: 72×72px · 603 B · drawable-xxxhdpi: 96×96px · 847 B · drawable-anydpi: XML · 764 B |
| `ic_volume` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 252 B · drawable-xhdpi: 48×48px · 264 B · drawable-xxhdpi: 72×72px · 366 B |
| `ic_volume_down` | webp | 30 × 30 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 45×45px · 270 B · drawable-xhdpi: 60×60px · 344 B · drawable-xxhdpi: 90×90px · 412 B |
| `ic_volume_mute` | webp | 32 × 32 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 48×48px · 396 B · drawable-xhdpi: 64×64px · 602 B · drawable-xxhdpi: 96×96px · 670 B |
| `ic_volume_off` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 388 B · drawable-xhdpi: 48×48px · 574 B · drawable-xxhdpi: 72×72px · 664 B |
| `ic_volume_off_btn` | webp | 30 × 30 dp (consistent across xxhdpi) | drawable-xxhdpi: 90×90px · 1646 B |
| `ic_volume_on` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 406 B · drawable-xhdpi: 48×48px · 570 B · drawable-xxhdpi: 72×72px · 668 B |
| `ic_volume_on_btn` | webp | 30 × 30 dp (consistent across xxhdpi) | drawable-xxhdpi: 90×90px · 1502 B |
| `ic_volume_playing` | webp | 32 × 32 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 48×48px · 352 B · drawable-xhdpi: 64×64px · 516 B · drawable-xxhdpi: 96×96px · 642 B |
| `ic_volume_up` | webp | 30 × 30 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 45×45px · 450 B · drawable-xhdpi: 60×60px · 634 B · drawable-xxhdpi: 90×90px · 796 B |
| `ic_zoom_custom` | webp | 28 × 28 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 42×42px · 1162 B · drawable-xhdpi: 56×56px · 1602 B · drawable-xxhdpi: 84×84px · 1562 B |
| `ic_zoom_inside` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 138 B · drawable-xhdpi: 48×48px · 126 B · drawable-xxhdpi: 72×72px · 178 B |
| `ic_zoom_inside_more` | webp | 28 × 28 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 42×42px · 348 B · drawable-xhdpi: 56×56px · 498 B · drawable-xxhdpi: 84×84px · 626 B |
| `ic_zoom_original` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 160 B · drawable-xhdpi: 48×48px · 154 B · drawable-xxhdpi: 72×72px · 214 B |
| `ic_zoom_original_more` | webp | 28 × 28 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 42×42px · 378 B · drawable-xhdpi: 56×56px · 506 B · drawable-xxhdpi: 84×84px · 632 B |
| `ic_zoom_stretch` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 284 B · drawable-xhdpi: 48×48px · 316 B · drawable-xxhdpi: 72×72px · 416 B |
| `ic_zoom_stretch_more` | webp | 28 × 28 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 42×42px · 570 B · drawable-xhdpi: 56×56px · 772 B · drawable-xxhdpi: 84×84px · 1026 B |
| `icon_display_lock_large_white` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 278 B · drawable-xhdpi: 48×48px · 336 B · drawable-xxhdpi: 72×72px · 462 B |
| `icon_menu_decoder` | bitmap | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 300 B |
| `icon_menu_subtitle` | bitmap | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 300 B |
| `icon_progressbar_blue` | 9.png | 10.6667 × 12.6667 dp (from xxhdpi; densities disagree slightly: hdpi→11.3333×13.3333, ldpi→12×14.6667, xhdpi→11×13, xxhdpi→10.6667×12.6667) | drawable-ldpi: 9×11px · 188 B · drawable-hdpi: 17×20px · 207 B · drawable-xhdpi: 22×26px · 213 B · drawable-xxhdpi: 32×38px · 219 B |
| `icon_progressbar_default` | 9.png | 10.6667 × 12.6667 dp (from xxhdpi; densities disagree slightly: hdpi→11.3333×13.3333, ldpi→12×14.6667, xhdpi→11×13, xxhdpi→10.6667×12.6667) | drawable-ldpi: 9×11px · 197 B · drawable-hdpi: 17×20px · 201 B · drawable-xhdpi: 22×26px · 202 B · drawable-xxhdpi: 32×38px · 211 B |
| `icon_speed` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 474 B · drawable-xhdpi: 48×48px · 710 B · drawable-xxhdpi: 72×72px · 916 B |
| `icon_subtitle` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 226 B · drawable-xhdpi: 48×48px · 236 B · drawable-xxhdpi: 72×72px · 288 B |
| `menu_seek_bar_progress` | selector | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 2404 B |
| `menu_seek_bar_thumb` | selector | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 2244 B |
| `mxskin__bg_icon_display_lock__dark` | webp | 14 × 14 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 21×21px · 254 B · drawable-xhdpi: 28×28px · 360 B · drawable-xxhdpi: 42×42px · 482 B |
| `mxskin__ic_more_speed__dark` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 542 B · drawable-xhdpi: 48×48px · 792 B · drawable-xxhdpi: 72×72px · 1068 B |
| `mxskin__ic_more_speed__light` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 616 B · drawable-xhdpi: 48×48px · 846 B · drawable-xxhdpi: 72×72px · 1192 B |
| `mxskin__ic_more_subtitle__dark` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 284 B · drawable-xhdpi: 48×48px · 274 B · drawable-xxhdpi: 72×72px · 410 B |
| `mxskin__ic_more_subtitle__light` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 326 B · drawable-xhdpi: 48×48px · 356 B · drawable-xxhdpi: 72×72px · 558 B |
| `mxskin__ic_private_folder_unlock__light` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 434 B · drawable-xhdpi: 48×48px · 550 B · drawable-xxhdpi: 72×72px · 774 B |
| `mxskin__player_seekbar_progress__dark` | layer-list | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 1048 B |
| `mxskin__player_seekbar_thumb__dark` | selector | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 340 B |
| `mxskin__sleep_timer__dark` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 564 B · drawable-xhdpi: 48×48px · 792 B · drawable-xxhdpi: 72×72px · 1154 B |
| `pip_bound` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 190 B · drawable-xhdpi: 48×48px · 238 B · drawable-xxhdpi: 72×72px · 196 B |
| `progressbar_blue_radius_dp2` | layer-list | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 700 B |
| `progressbar_transparent` | layer-list | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 700 B |
| `sleep_timer_enable` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 772 B · drawable-xhdpi: 48×48px · 1020 B · drawable-xxhdpi: 72×72px · 1190 B |
| `soundbar_progress` | layer-list | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 1684 B |
| `speed_seek_bar_progress` | selector | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 1380 B |
| `speed_seek_bar_thumb` | selector | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 1468 B |
| `speed_seek_bar_thumb_small` | selector | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 1468 B |
| `tap_seek_bg` | selector | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 628 B |
| `timer` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 470 B · drawable-xhdpi: 48×48px · 568 B · drawable-xxhdpi: 72×72px · 818 B |

## Video library / list & grid (catalogue group: 187) — 187 entries catalogued below

| Catalogue name | Type | Derived size (dp) | Densities measured (folder: px) |
|---|---|---|---|
| `abc_ic_search_api_material` | vector | 24.0dip × 24.0dip (vector android:width/height) | drawable: XML · 812 B |
| `bg_rectangle_clip_4_sides_selected` | vector | 77.0dip × 36.0dip (vector android:width/height) | drawable: XML · 956 B |
| `bg_recycle_bin_item_duration` | shape | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 484 B |
| `check_box_big_checked` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 358 B · drawable-xhdpi: 48×48px · 464 B · drawable-xxhdpi: 72×72px · 682 B |
| `check_box_big_unchecked_light` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 250 B · drawable-xhdpi: 48×48px · 284 B · drawable-xxhdpi: 72×72px · 410 B |
| `check_box_black_unchecked_light` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 244 B · drawable-xhdpi: 48×48px · 282 B · drawable-xxhdpi: 72×72px · 372 B |
| `check_box_button` | selector | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 464 B |
| `check_box_button_black` | selector | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 628 B |
| `check_box_button_watchlist` | selector | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 464 B |
| `check_box_button_white` | selector | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 464 B |
| `check_box_checked` | webp | 20 × 20 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 30×30px · 412 B · drawable-xhdpi: 40×40px · 430 B · drawable-xxhdpi: 60×60px · 688 B |
| `check_box_checked_24dp_3c8cf0` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 358 B · drawable-xhdpi: 48×48px · 464 B · drawable-xxhdpi: 72×72px · 682 B |
| `check_box_checked_bigger` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 328 B · drawable-xhdpi: 48×48px · 460 B · drawable-xxhdpi: 72×72px · 278 B |
| `check_box_common_drawable` | selector | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 464 B |
| `check_box_uncheck_24dp_85929c` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 234 B · drawable-xhdpi: 48×48px · 262 B · drawable-xxhdpi: 72×72px · 396 B |
| `check_box_uncheck_505a78` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 248 B · drawable-xhdpi: 48×48px · 290 B · drawable-xxhdpi: 72×72px · 432 B |
| `check_box_unchecked_bigger` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 240 B · drawable-xhdpi: 48×48px · 290 B · drawable-xxhdpi: 72×72px · 166 B |
| `check_box_unchecked_light` | webp | 20 × 20 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 30×30px · 248 B · drawable-xhdpi: 40×40px · 314 B · drawable-xxhdpi: 60×60px · 394 B |
| `check_box_unchecked_white` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 214 B · drawable-xhdpi: 48×48px · 196 B · drawable-xxhdpi: 72×72px · 288 B |
| `common_offline_selector` | selector | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 1032 B |
| `create_folder_edittext_bottom` | xml (binary — NOT in resources.arsc, not decoded) | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 720 B |
| `default_video` | 9.png | 40 × 40 dp (consistent across xxhdpi) | drawable-xxhdpi: 120×120px · 1435 B |
| `default_video_big` | 9.png | 45 × 45 dp (consistent across xxxhdpi) | drawable-xxxhdpi: 180×180px · 2357 B |
| `et_underline_selected` | layer-list | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 848 B |
| `et_underline_selector` | selector | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 484 B |
| `et_underline_unselected` | layer-list | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 848 B |
| `fc_folder` | webp | 21.3333 × 21.3333 dp (consistent across hdpi) | drawable-hdpi: 32×32px · 1004 B |
| `folder` | webp | 56 × 56 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 84×84px · 354 B · drawable-xhdpi: 112×112px · 416 B · drawable-xxhdpi: 168×168px · 572 B |
| `folder_1` | webp (zip-only — NOT in resources.arsc) | 56 × 56 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 84×84px · 350 B · drawable-xhdpi: 112×112px · 418 B · drawable-xxhdpi: 168×168px · 590 B |
| `folder_back` | webp (zip-only — NOT in resources.arsc) | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 236 B · drawable-xhdpi: 48×48px · 308 B · drawable-xxhdpi: 72×72px · 258 B |
| `history_progress_bar` | layer-list | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 1200 B |
| `home_offline_selector` | selector | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 1204 B |
| `hotsearch_play` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 326 B · drawable-xhdpi: 48×48px · 396 B · drawable-xxhdpi: 72×72px · 222 B |
| `ic_add_playlist` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 320 B · drawable-xhdpi: 48×48px · 328 B · drawable-xxhdpi: 72×72px · 500 B |
| `ic_app_language_navigation__light` | webp | 20 × 20 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 30×30px · 594 B · drawable-xhdpi: 40×40px · 816 B · drawable-xxhdpi: 60×60px · 1120 B |
| `ic_app_theme_navigation__light` | webp | 20 × 20 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 30×30px · 572 B · drawable-xhdpi: 40×40px · 766 B · drawable-xxhdpi: 60×60px · 1058 B |
| `ic_check_box_outline_blank_white` | webp | 12 × 12 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 18×18px · 202 B · drawable-xhdpi: 24×24px · 276 B · drawable-xxhdpi: 36×36px · 120 B |
| `ic_check_box_white` | webp | 12 × 12 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 18×18px · 222 B · drawable-xhdpi: 24×24px · 262 B · drawable-xxhdpi: 36×36px · 204 B |
| `ic_create_folder` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 484 B · drawable-xhdpi: 48×48px · 604 B · drawable-xxhdpi: 72×72px · 826 B |
| `ic_create_playlist` | webp | 16 × 16 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 24×24px · 244 B · drawable-xhdpi: 32×32px · 330 B · drawable-xxhdpi: 48×48px · 354 B |
| `ic_default_cancel` | vector + raster (png) | 24.0dip × 24.0dip (vector android:width/height) | drawable: XML · 648 B · drawable-hdpi: 36×36px · 237 B · drawable-xhdpi: 48×48px · 327 B · drawable-xxhdpi: 72×72px · 356 B |
| `ic_empty_data_new` | webp | 84 × 84 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 126×126px · 4486 B · drawable-xhdpi: 168×168px · 8822 B · drawable-xxhdpi: 252×252px · 7438 B |
| `ic_empty_folder` | webp | 160 × 160 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 240×240px · 9436 B · drawable-xhdpi: 320×320px · 14048 B · drawable-xxhdpi: 480×480px · 24610 B |
| `ic_file_selected` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 352 B · drawable-xhdpi: 48×48px · 478 B · drawable-xxhdpi: 72×72px · 548 B |
| `ic_file_small` | webp | 16 × 16 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 24×24px · 174 B · drawable-xhdpi: 32×32px · 204 B · drawable-xxhdpi: 48×48px · 214 B |
| `ic_folder_internal` | png | 9.66667 × 7.33333 dp (from xxhdpi; densities disagree slightly: hdpi→10×7.33333, xhdpi→9.5×7.5, xxhdpi→9.66667×7.33333) | drawable-hdpi: 15×11px · 157 B · drawable-xhdpi: 19×15px · 213 B · drawable-xxhdpi: 29×22px · 218 B |
| `ic_folder_sdcard` | png | 7.33333 × 9 dp (from xxhdpi; densities disagree slightly: hdpi→7.33333×9.33333, xhdpi→7.5×9, xxhdpi→7.33333×9) | drawable-hdpi: 11×14px · 257 B · drawable-xhdpi: 15×18px · 329 B · drawable-xxhdpi: 22×27px · 316 B |
| `ic_folder_white_24dp` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 100 B · drawable-xhdpi: 48×48px · 138 B · drawable-xxhdpi: 72×72px · 176 B |
| `ic_grid` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 160 B · drawable-xhdpi: 48×48px · 102 B · drawable-xxhdpi: 72×72px · 130 B |
| `ic_help_navigation__light` | webp | 20 × 20 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 30×30px · 722 B · drawable-xhdpi: 40×40px · 1012 B · drawable-xxhdpi: 60×60px · 1516 B |
| `ic_list_white_24dp` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 82 B · drawable-xhdpi: 48×48px · 64 B · drawable-xxhdpi: 72×72px · 66 B |
| `ic_local_cover_pile` | webp | 44 × 4 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 66×6px · 190 B · drawable-xhdpi: 88×8px · 206 B · drawable-xxhdpi: 132×12px · 218 B |
| `ic_local_file` | webp | 12 × 12 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 18×18px · 178 B · drawable-xhdpi: 24×24px · 212 B · drawable-xxhdpi: 36×36px · 158 B |
| `ic_local_last_play` | webp | 84 × 84 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 126×126px · 2674 B · drawable-xhdpi: 168×168px · 3962 B · drawable-xxhdpi: 252×252px · 6058 B |
| `ic_local_network_medium` | webp | 28 × 26 dp (from xxhdpi; densities disagree slightly: hdpi→18.6667×17.3333, xhdpi→28×26, xxhdpi→28×26) | drawable-hdpi: 28×26px · 150 B · drawable-xhdpi: 56×52px · 204 B · drawable-xxhdpi: 84×78px · 260 B |
| `ic_local_network_normal` | webp | 48 × 52 dp (from xxhdpi; densities disagree slightly: hdpi→32×34.6667, xhdpi→48×52, xxhdpi→48×52) | drawable-hdpi: 48×52px · 290 B · drawable-xhdpi: 96×104px · 426 B · drawable-xxhdpi: 144×156px · 592 B |
| `ic_local_network_small` | webp | 24 × 24 dp (from xxhdpi; densities disagree slightly: hdpi→16×16, xhdpi→24×24, xxhdpi→24×24) | drawable-hdpi: 24×24px · 166 B · drawable-xhdpi: 48×48px · 232 B · drawable-xxhdpi: 72×72px · 284 B |
| `ic_local_private_folder__light` | webp | 20 × 20 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 30×30px · 550 B · drawable-xhdpi: 40×40px · 724 B · drawable-xxhdpi: 60×60px · 986 B |
| `ic_localisatiion_app_languages_tips_close` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 388 B · drawable-xhdpi: 48×48px · 522 B · drawable-xxhdpi: 72×72px · 356 B |
| `ic_localnetwork_navigation__light` | webp | 20 × 20 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 30×30px · 280 B · drawable-xhdpi: 40×40px · 366 B · drawable-xxhdpi: 60×60px · 340 B |
| `ic_media_control_pause` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 490 B · drawable-xhdpi: 48×48px · 682 B · drawable-xxhdpi: 72×72px · 926 B |
| `ic_media_control_play` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 528 B · drawable-xhdpi: 48×48px · 822 B · drawable-xxhdpi: 72×72px · 1086 B |
| `ic_media_folder` | webp | 12 × 12 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 18×18px · 198 B · drawable-xhdpi: 24×24px · 200 B · drawable-xxhdpi: 36×36px · 248 B |
| `ic_media_manager_added` | webp | 32 × 32 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 48×48px · 796 B · drawable-xhdpi: 64×64px · 1098 B · drawable-xxhdpi: 96×96px · 1542 B |
| `ic_media_manager_big` | webp | 32 × 32 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 48×48px · 702 B · drawable-xhdpi: 64×64px · 1016 B · drawable-xxhdpi: 96×96px · 1282 B |
| `ic_media_manager_recently` | webp | 32 × 32 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 48×48px · 816 B · drawable-xhdpi: 64×64px · 1138 B · drawable-xxhdpi: 96×96px · 1562 B |
| `ic_media_manager_warning` | webp | 12 × 12 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 18×18px · 362 B · drawable-xhdpi: 24×24px · 344 B · drawable-xxhdpi: 36×36px · 570 B |
| `ic_media_manger_scan_finish` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 650 B · drawable-xhdpi: 48×48px · 774 B · drawable-xxhdpi: 72×72px · 1220 B |
| `ic_media_pause_dark` | png | 48 × 48 dp (consistent across hdpi, mdpi, xhdpi, xxhdpi) | drawable-mdpi: 48×48px · 90 B · drawable-hdpi: 72×72px · 92 B · drawable-xhdpi: 96×96px · 94 B · drawable-xxhdpi: 144×144px · 110 B |
| `ic_media_play_dark` | png | 48 × 48 dp (consistent across hdpi, mdpi, xhdpi, xxhdpi) | drawable-mdpi: 48×48px · 214 B · drawable-hdpi: 72×72px · 279 B · drawable-xhdpi: 96×96px · 343 B · drawable-xxhdpi: 144×144px · 461 B |
| `ic_move_folder` | webp | 56 × 56 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 84×84px · 356 B · drawable-xhdpi: 112×112px · 414 B · drawable-xxhdpi: 168×168px · 588 B |
| `ic_move_white_24dp` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 422 B · drawable-xhdpi: 48×48px · 564 B · drawable-xxhdpi: 72×72px · 658 B |
| `ic_navigation_indian_my_list` | webp | 32 × 32 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 48×48px · 320 B · drawable-xhdpi: 64×64px · 384 B · drawable-xxhdpi: 96×96px · 454 B |
| `ic_navigation_private_folder` | webp | 20 × 20 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 30×30px · 372 B · drawable-xhdpi: 40×40px · 526 B · drawable-xxhdpi: 60×60px · 526 B |
| `ic_no_media_files` | webp | 84 × 84 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 126×126px · 2956 B · drawable-xhdpi: 168×168px · 5184 B · drawable-xxhdpi: 252×252px · 7770 B |
| `ic_openurl_navigation__light` | webp | 20 × 20 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 30×30px · 622 B · drawable-xhdpi: 40×40px · 860 B · drawable-xxhdpi: 60×60px · 1314 B |
| `ic_options_menu_arrow_downward` | webp | 12 × 12 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 18×18px · 198 B · drawable-xhdpi: 24×24px · 278 B · drawable-xxhdpi: 36×36px · 182 B |
| `ic_options_menu_arrow_upward` | webp | 12 × 12 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 18×18px · 196 B · drawable-xhdpi: 24×24px · 268 B · drawable-xxhdpi: 36×36px · 182 B |
| `ic_options_menu_layout_grid` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 330 B · drawable-xhdpi: 48×48px · 370 B · drawable-xxhdpi: 72×72px · 636 B |
| `ic_options_menu_layout_list` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 348 B · drawable-xhdpi: 48×48px · 392 B · drawable-xxhdpi: 72×72px · 560 B |
| `ic_options_menu_sort_title` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 576 B · drawable-xhdpi: 48×48px · 872 B · drawable-xxhdpi: 72×72px · 1130 B |
| `ic_options_menu_view_mode_allfolders` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 548 B · drawable-xhdpi: 48×48px · 692 B · drawable-xxhdpi: 72×72px · 1028 B |
| `ic_options_menu_view_mode_folders` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 320 B · drawable-xhdpi: 48×48px · 384 B · drawable-xxhdpi: 72×72px · 532 B |
| `ic_original_tab_local_normal` | webp | 22 × 22 dp (from xxhdpi; densities disagree slightly: hdpi→14.6667×14.6667, xhdpi→22×22, xxhdpi→22×22) | drawable-hdpi: 22×22px · 194 B · drawable-xhdpi: 44×44px · 246 B · drawable-xxhdpi: 66×66px · 298 B |
| `ic_playlist_remove` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 476 B · drawable-xhdpi: 48×48px · 616 B · drawable-xxhdpi: 72×72px · 846 B |
| `ic_private_file_add` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 188 B · drawable-xhdpi: 48×48px · 176 B · drawable-xxhdpi: 72×72px · 260 B |
| `ic_private_file_menu_icon` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 240 B · drawable-xhdpi: 48×48px · 292 B · drawable-xxhdpi: 72×72px · 396 B |
| `ic_private_folder` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 428 B · drawable-xhdpi: 48×48px · 572 B · drawable-xxhdpi: 72×72px · 690 B |
| `ic_private_folder_arrow_back` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 206 B · drawable-xhdpi: 48×48px · 204 B · drawable-xxhdpi: 72×72px · 260 B |
| `ic_private_folder_gray` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 304 B · drawable-xhdpi: 48×48px · 398 B · drawable-xxhdpi: 72×72px · 484 B |
| `ic_private_folder_holo` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 412 B · drawable-xhdpi: 48×48px · 570 B · drawable-xxhdpi: 72×72px · 766 B |
| `ic_private_folder_rectangle_selected` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 348 B · drawable-xhdpi: 48×48px · 464 B · drawable-xxhdpi: 72×72px · 580 B |
| `ic_private_folder_rectangle_unselected` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 246 B · drawable-xhdpi: 48×48px · 282 B · drawable-xxhdpi: 72×72px · 304 B |
| `ic_private_folder_success` | webp | 45 × 45 dp (from xxhdpi; densities disagree slightly: hdpi→60×60, xhdpi→90×90, xxhdpi→45×45) | drawable-hdpi: 90×90px · 1534 B · drawable-xhdpi: 180×180px · 2874 B · drawable-xxhdpi: 135×135px · 2138 B |
| `ic_recycle_bin_empty` | webp | 100 × 100 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 150×150px · 3430 B · drawable-xhdpi: 200×200px · 5728 B · drawable-xxhdpi: 300×300px · 10094 B |
| `ic_recycle_bin_tips` | webp | 10 × 10 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 15×15px · 306 B · drawable-xhdpi: 20×20px · 544 B · drawable-xxhdpi: 30×30px · 556 B |
| `ic_refresh_white_24dp` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 322 B · drawable-xhdpi: 48×48px · 418 B · drawable-xxhdpi: 72×72px · 606 B |
| `ic_save_to_file` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 332 B · drawable-xhdpi: 48×48px · 402 B · drawable-xxhdpi: 72×72px · 476 B |
| `ic_search_no_ret` | webp | 120 × 120 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 180×180px · 3508 B · drawable-xhdpi: 240×240px · 5006 B · drawable-xxhdpi: 360×360px · 7556 B |
| `ic_search_white_24dp` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 336 B · drawable-xhdpi: 48×48px · 408 B · drawable-xxhdpi: 72×72px · 626 B |
| `ic_small_file_folder` | webp | 20 × 20 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 30×30px · 182 B · drawable-xhdpi: 40×40px · 222 B · drawable-xxhdpi: 60×60px · 278 B |
| `ic_sort_big_files` | webp | 20 × 20 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 30×30px · 542 B · drawable-xhdpi: 40×40px · 812 B · drawable-xxhdpi: 60×60px · 1094 B |
| `ic_sort_not_played` | webp | 20 × 20 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 30×30px · 278 B · drawable-xhdpi: 40×40px · 374 B · drawable-xxhdpi: 60×60px · 442 B |
| `ic_sort_recently_played` | webp | 20 × 20 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 30×30px · 514 B · drawable-xhdpi: 40×40px · 598 B · drawable-xxhdpi: 60×60px · 916 B |
| `ic_sort_title` | webp | 20 × 20 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 30×30px · 406 B · drawable-xhdpi: 40×40px · 544 B · drawable-xxhdpi: 60×60px · 690 B |
| `ic_statusbar_folder` | webp | 32 × 32 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 48×48px · 210 B · drawable-xhdpi: 64×64px · 326 B · drawable-xxhdpi: 96×96px · 286 B |
| `ic_tab_local_normal_dark` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 258 B · drawable-xhdpi: 48×48px · 322 B · drawable-xxhdpi: 72×72px · 304 B |
| `ic_tab_local_selected` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 330 B · drawable-xhdpi: 48×48px · 300 B · drawable-xxhdpi: 72×72px · 218 B |
| `ic_usb_storage_navigation__light` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 370 B · drawable-xhdpi: 48×48px · 420 B · drawable-xxhdpi: 72×72px · 670 B |
| `ic_watch_history` | webp | 20 × 20 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 30×30px · 516 B · drawable-xhdpi: 40×40px · 834 B · drawable-xxhdpi: 60×60px · 958 B |
| `icon_24_action_private_folder` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 504 B · drawable-xhdpi: 48×48px · 716 B · drawable-xxhdpi: 72×72px · 972 B |
| `icon_24_action_search` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 414 B · drawable-xhdpi: 48×48px · 544 B · drawable-xxhdpi: 72×72px · 808 B |
| `icon_folder_mini` | webp (zip-only — NOT in resources.arsc) | 12 × 12 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 18×18px · 198 B · drawable-xhdpi: 24×24px · 200 B · drawable-xxhdpi: 36×36px · 248 B |
| `icon_radio_button_not_selecte` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 474 B · drawable-xhdpi: 48×48px · 620 B · drawable-xxhdpi: 72×72px · 954 B |
| `icon_radio_button_selected` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 680 B · drawable-xhdpi: 48×48px · 904 B · drawable-xxhdpi: 72×72px · 1436 B |
| `img_empty_no_connection` | webp | 120 × 120 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 180×180px · 5154 B · drawable-xhdpi: 240×240px · 7694 B · drawable-xxhdpi: 360×360px · 11066 B |
| `img_empty_no_recommendation` | webp | 120 × 120 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 180×180px · 5054 B · drawable-xhdpi: 240×240px · 8600 B · drawable-xxhdpi: 360×360px · 12002 B |
| `img_empyt_no_file` | webp | 66.6667 × 66.6667 dp (from xxhdpi; densities disagree slightly: hdpi→66.6667×66.6667, xhdpi→66.5×66.5, xxhdpi→66.6667×66.6667) | drawable-hdpi: 100×100px · 2698 B · drawable-xhdpi: 133×133px · 4808 B · drawable-xxhdpi: 200×200px · 6946 B |
| `mx_search_video_logo` | webp | 97 × 22 dp (from xxhdpi; densities disagree slightly: hdpi→97.3333×22, xhdpi→97×22, xxhdpi→97×22) | drawable-hdpi: 146×33px · 1718 B · drawable-xhdpi: 194×44px · 2676 B · drawable-xxhdpi: 291×66px · 2378 B |
| `mxskin__bg_playlist_item__light` | webp | 98 × 60 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 147×90px · 600 B · drawable-xhdpi: 196×120px · 762 B · drawable-xxhdpi: 294×180px · 1170 B |
| `mxskin__bug_report_type_bg_selector__light` | selector | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 468 B |
| `mxskin__button_ok_text_selector__light` | selector | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 464 B |
| `mxskin__check_box_big__light` | selector | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 464 B |
| `mxskin__check_box_black__light` | selector | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 464 B |
| `mxskin__check_select_all_unselect__dark` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 222 B · drawable-xhdpi: 48×48px · 250 B · drawable-xxhdpi: 72×72px · 224 B |
| `mxskin__choose_create_folder_btn__dark` | xml (binary — NOT in resources.arsc, not decoded) | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 484 B |
| `mxskin__choose_create_folder_btn__light` | xml (binary — NOT in resources.arsc, not decoded) | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 484 B |
| `mxskin__file_pdf_icon__dark` | webp (zip-only — NOT in resources.arsc) | 44 × 44 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 66×66px · 440 B · drawable-xhdpi: 88×88px · 654 B · drawable-xxhdpi: 132×132px · 834 B |
| `mxskin__file_pdf_icon__light` | webp (zip-only — NOT in resources.arsc) | 44 × 44 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 66×66px · 602 B · drawable-xhdpi: 88×88px · 894 B · drawable-xxhdpi: 132×132px · 648 B |
| `mxskin__file_ppt_icon__dark` | webp (zip-only — NOT in resources.arsc) | 45 × 45 dp (from xxhdpi; densities disagree slightly: hdpi→45.3333×45.3333, xhdpi→45×45, xxhdpi→45×45) | drawable-hdpi: 68×68px · 536 B · drawable-xhdpi: 90×90px · 660 B · drawable-xxhdpi: 135×135px · 840 B |
| `mxskin__file_ppt_icon__light` | webp (zip-only — NOT in resources.arsc) | 44 × 45 dp (from xxhdpi; densities disagree slightly: hdpi→44×45.3333, xhdpi→44×45, xxhdpi→44×45) | drawable-hdpi: 66×68px · 630 B · drawable-xhdpi: 88×90px · 878 B · drawable-xxhdpi: 132×135px · 650 B |
| `mxskin__file_word_icon__dark` | webp (zip-only — NOT in resources.arsc) | 45 × 45 dp (from xxhdpi; densities disagree slightly: hdpi→45.3333×45.3333, xhdpi→45×45, xxhdpi→45×45) | drawable-hdpi: 68×68px · 564 B · drawable-xhdpi: 90×90px · 728 B · drawable-xxhdpi: 135×135px · 884 B |
| `mxskin__file_word_icon__light` | webp (zip-only — NOT in resources.arsc) | 44 × 44 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 66×66px · 654 B · drawable-xhdpi: 88×88px · 934 B · drawable-xxhdpi: 132×132px · 776 B |
| `mxskin__file_wps_icon__dark` | webp (zip-only — NOT in resources.arsc) | 45 × 45 dp (from xxhdpi; densities disagree slightly: hdpi→45.3333×45.3333, xhdpi→45×45, xxhdpi→45×45) | drawable-hdpi: 68×68px · 576 B · drawable-xhdpi: 90×90px · 696 B · drawable-xxhdpi: 135×135px · 872 B |
| `mxskin__file_wps_icon__light` | webp (zip-only — NOT in resources.arsc) | 44 × 45 dp (from xxhdpi; densities disagree slightly: hdpi→44×45.3333, xhdpi→44×45, xxhdpi→44×45) | drawable-hdpi: 66×68px · 670 B · drawable-xhdpi: 88×90px · 948 B · drawable-xxhdpi: 132×135px · 760 B |
| `mxskin__file_xls_icon__dark` | webp (zip-only — NOT in resources.arsc) | 45 × 45 dp (from xxhdpi; densities disagree slightly: hdpi→45.3333×45.3333, xhdpi→45×45, xxhdpi→45×45) | drawable-hdpi: 68×68px · 516 B · drawable-xhdpi: 90×90px · 632 B · drawable-xxhdpi: 135×135px · 776 B |
| `mxskin__file_xls_icon__light` | webp (zip-only — NOT in resources.arsc) | 44 × 45 dp (from xxhdpi; densities disagree slightly: hdpi→44×45.3333, xhdpi→44×45, xxhdpi→44×45) | drawable-hdpi: 66×68px · 604 B · drawable-xhdpi: 88×90px · 834 B · drawable-xxhdpi: 132×135px · 608 B |
| `mxskin__file_zip_icon__dark` | webp (zip-only — NOT in resources.arsc) | 45 × 45 dp (from xxhdpi; densities disagree slightly: hdpi→45.3333×45.3333, xhdpi→45×45, xxhdpi→45×45) | drawable-hdpi: 68×68px · 550 B · drawable-xhdpi: 90×90px · 616 B · drawable-xxhdpi: 135×135px · 620 B |
| `mxskin__file_zip_icon__light` | webp (zip-only — NOT in resources.arsc) | 44 × 45 dp (from xxhdpi; densities disagree slightly: hdpi→44×45.3333, xhdpi→44×45, xxhdpi→44×45) | drawable-hdpi: 66×68px · 514 B · drawable-xhdpi: 88×90px · 622 B · drawable-xxhdpi: 132×135px · 338 B |
| `mxskin__filter_check_box_button__light` | selector | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 464 B |
| `mxskin__ic_action_add_to_my_list__light` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 196 B · drawable-xhdpi: 48×48px · 200 B · drawable-xxhdpi: 72×72px · 204 B |
| `mxskin__ic_action_history__light` | vector | 16.0dip × 16.0dip (vector android:width/height) | drawable: XML · 1080 B |
| `mxskin__ic_arrow_navigation__light` | webp | 25 × 24 dp (from xxhdpi; densities disagree slightly: hdpi→25.3333×24, xhdpi→25×24, xxhdpi→25×24) | drawable-hdpi: 38×36px · 298 B · drawable-xhdpi: 50×48px · 262 B · drawable-xxhdpi: 75×72px · 234 B |
| `mxskin__ic_create_folder__light` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 230 B · drawable-xhdpi: 48×48px · 206 B · drawable-xxhdpi: 72×72px · 140 B |
| `mxskin__ic_default_folder__light` | webp | 42.6667 × 42.6667 dp (from xxhdpi; densities disagree slightly: hdpi→42.6667×42.6667, xhdpi→48×48, xxhdpi→42.6667×42.6667) | drawable-hdpi: 64×64px · 234 B · drawable-xhdpi: 96×96px · 270 B · drawable-xxhdpi: 128×128px · 338 B |
| `mxskin__ic_drawer_navigation__light` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 152 B · drawable-xhdpi: 48×48px · 154 B · drawable-xxhdpi: 72×72px · 98 B |
| `mxskin__ic_folder__dark` | webp (zip-only — NOT in resources.arsc) | 85.3333 × 85.3333 dp (from xxhdpi; densities disagree slightly: hdpi→85.3333×85.3333, xhdpi→85.5×85.5, xxhdpi→85.3333×85.3333) | drawable-hdpi: 128×128px · 596 B · drawable-xhdpi: 171×171px · 860 B · drawable-xxhdpi: 256×256px · 1010 B |
| `mxskin__ic_folder__light` | webp (zip-only — NOT in resources.arsc) | 64 × 64 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 96×96px · 358 B · drawable-xhdpi: 128×128px · 504 B · drawable-xxhdpi: 192×192px · 640 B |
| `mxskin__ic_media_manager_image__light` | webp | 20 × 20 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 30×30px · 460 B · drawable-xhdpi: 40×40px · 636 B · drawable-xxhdpi: 60×60px · 920 B |
| `mxskin__ic_media_manager_video__light` | webp | 20 × 20 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 30×30px · 458 B · drawable-xhdpi: 40×40px · 640 B · drawable-xxhdpi: 60×60px · 994 B |
| `mxskin__ic_private_folder_rectangle_unselected__light` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 250 B · drawable-xhdpi: 48×48px · 284 B · drawable-xxhdpi: 72×72px · 410 B |
| `mxskin__ic_private_folder_sort__light` | webp | 42 × 42 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 63×63px · 314 B · drawable-xhdpi: 84×84px · 386 B · drawable-xxhdpi: 126×126px · 462 B |
| `mxskin__ic_search__light` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 404 B · drawable-xhdpi: 48×48px · 606 B · drawable-xxhdpi: 72×72px · 710 B |
| `mxskin__ic_search_accent__light` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 438 B · drawable-xhdpi: 48×48px · 728 B · drawable-xxhdpi: 72×72px · 880 B |
| `mxskin__ic_search_error__light` | webp | 160 × 160 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 240×240px · 7038 B · drawable-xhdpi: 320×320px · 10330 B · drawable-xxhdpi: 480×480px · 15114 B |
| `mxskin__ic_search_tube__light` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 418 B · drawable-xhdpi: 48×48px · 556 B · drawable-xxhdpi: 72×72px · 890 B |
| `mxskin__ic_sort__light` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 276 B · drawable-xhdpi: 48×48px · 364 B · drawable-xxhdpi: 72×72px · 348 B |
| `mxskin__icon_action_tab__light` | webp | 12 × 12 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 18×18px · 252 B · drawable-xhdpi: 24×24px · 304 B · drawable-xxhdpi: 36×36px · 396 B |
| `mxskin__layer_list_progress__light` | layer-list | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 548 B |
| `mxskin__list_folder_usb__light` | webp | 64 × 64 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 96×96px · 382 B · drawable-xhdpi: 128×128px · 540 B · drawable-xxhdpi: 192×192px · 732 B |
| `mxskin__local_last_play_icon__dark` | layer-list | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 344 B |
| `mxskin__mx_home_tab_bg__light` | selector | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 340 B |
| `mxskin__mx_new_voice_search__light` | vector | 24.0dip × 24.0dip (vector android:width/height) | drawable: XML · 1348 B |
| `mxskin__mx_voice_search__light` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 440 B · drawable-xhdpi: 48×48px · 546 B · drawable-xxhdpi: 72×72px · 692 B |
| `mxskin__navigation_indian_header_bg__light` | webp | 103.333 × 68 dp (from xxhdpi; densities disagree slightly: hdpi→103.333×68, xhdpi→103.5×68, xxhdpi→103.333×68) | drawable-hdpi: 155×102px · 376 B · drawable-xhdpi: 207×136px · 494 B · drawable-xxhdpi: 310×204px · 764 B |
| `mxskin__private_folder_continue_solid_bg__light` | selector | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 484 B |
| `mxskin__recycle_bin_recover__light` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 306 B · drawable-xhdpi: 48×48px · 340 B · drawable-xxhdpi: 72×72px · 454 B |
| `mxskin__remote_file_icon__light` | webp | 96 × 56 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 144×84px · 334 B · drawable-xhdpi: 192×112px · 412 B · drawable-xxhdpi: 288×168px · 470 B |
| `mxskin__tab_local__dark` | selector | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 468 B |
| `mxskin__tab_video_selected__dark` | webp (zip-only — NOT in resources.arsc) | 28 × 28 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 42×42px · 224 B · drawable-xhdpi: 56×56px · 274 B · drawable-xxhdpi: 84×84px · 278 B |
| `mxskin__tab_video_selected__light` | webp (zip-only — NOT in resources.arsc) | 28 × 28 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 42×42px · 346 B · drawable-xhdpi: 56×56px · 368 B · drawable-xxhdpi: 84×84px · 282 B |
| `mxskin__tab_video_unselect__dark` | webp (zip-only — NOT in resources.arsc) | 28 × 28 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 42×42px · 212 B · drawable-xhdpi: 56×56px · 254 B · drawable-xxhdpi: 84×84px · 256 B |
| `mxskin__tab_video_unselect__light` | webp (zip-only — NOT in resources.arsc) | 28 × 28 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 42×42px · 264 B · drawable-xhdpi: 56×56px · 324 B · drawable-xxhdpi: 84×84px · 272 B |
| `my_playlists` | webp | 151 × 17 dp (from xxhdpi; densities disagree slightly: hdpi→151.333×17.3333, xhdpi→151×17, xxhdpi→151×17) | drawable-hdpi: 227×26px · 1918 B · drawable-xhdpi: 302×34px · 2268 B · drawable-xxhdpi: 453×51px · 2570 B |
| `navigation_right_arrow` | webp | 24 × 24 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 36×36px · 204 B · drawable-xhdpi: 48×48px · 256 B · drawable-xxhdpi: 72×72px · 250 B |
| `playlist_deco` | selector | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 824 B |
| `radiobutton_background_selector` | selector | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 608 B |
| `selector_choose_audio_video_item_check` | xml (binary — NOT in resources.arsc, not decoded) | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 484 B |
| `sl_selected_light` | webp | 21.3333 × 21.3333 dp (from xxhdpi; densities disagree slightly: hdpi→21.3333×21.3333, xhdpi→21.5×21.5, xxhdpi→21.3333×21.3333) | drawable-hdpi: 32×32px · 380 B · drawable-xhdpi: 43×43px · 652 B · drawable-xxhdpi: 64×64px · 364 B |
| `smb_add_select_ic` | webp | 60 × 60 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 90×90px · 2846 B · drawable-xhdpi: 120×120px · 3966 B · drawable-xxhdpi: 180×180px · 3972 B |
| `smb_add_selector_btn` | selector | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 712 B |
| `smb_empty_ic` | webp | 48 × 49 dp (from xxhdpi; densities disagree slightly: hdpi→48×49.3333, xhdpi→48×49, xxhdpi→48×49) | drawable-hdpi: 72×74px · 1118 B · drawable-xhdpi: 96×98px · 1588 B · drawable-xxhdpi: 144×147px · 814 B |
| `switch_custom_thumb_selector` | selector | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 608 B |
| `switch_custom_track_selector` | selector | — (no intrinsic size in resources; composite or anydpi/nodpi) | drawable: XML · 608 B |
| `theme_list_black_mask` | webp | 88 × 56 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 132×84px · 1126 B · drawable-xhdpi: 176×112px · 1446 B · drawable-xxhdpi: 264×168px · 2338 B |
| `theme_list_light_mask` | webp | 88 × 56 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 132×84px · 1686 B · drawable-xhdpi: 176×112px · 2294 B · drawable-xxhdpi: 264×168px · 3478 B |
| `theme_list_system_mask` | webp | 88 × 56 dp (consistent across hdpi, xhdpi, xxhdpi) | drawable-hdpi: 132×84px · 1318 B · drawable-xhdpi: 176×112px · 1784 B · drawable-xxhdpi: 264×168px · 1552 B |

