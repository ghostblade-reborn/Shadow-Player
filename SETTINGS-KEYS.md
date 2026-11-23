# GhostBlade Player — Settings Keys (v0.2)

Every settings key in the app, where it is stored, its default, and
who consumes it. Keys and defaults are MX Player 2.12.4's own
(mined from the APK — see `carbon/MX-COPY-SPEC.md` §2.6–§2.8 and
`carbon/mining-screens.md` §3) wherever the mining resolved them.

## Storage model

- **Store** (`Store.kt`, `ghostplayer-state.json`): the canonical
  settings map. Keys are MX's preference keys; values are raw
  strings (booleans `"true"/"false"`, numbers in plain decimal,
  sets/lists as JSON arrays). Defaults live in
  `Settings.DEFAULTS` (`Settings.kt`) — storing a default removes
  the override. The settings screens read/write here.
- **PSet** (`PlayerActivity.kt`, Shadow Two): the player's own
  SharedPreferences store. Player-consumed keys are **bridged**:
  `SettingsActivity` reads them from PSet (what the player will
  actually do) and writes to both stores. The bridge table is in
  `SettingsActivity.bridge`; the mapping column below marks these
  keys **PSet-bridged**.
- Playlists shown in the UI live in `PSet.customPlaylists`
  (serialised `name \t path1|path2`), via `PlaylistStore`
  (`Surfaces.kt`) — the store Shadow Two declared for spec §2.9.
  `Store` keeps an equivalent playlist API (JVM-tested).

## Fix pass (Monitoring v0.2 P2-3): honest gating

Every settings node whose value nothing in this build reads is
now **gated** in `SettingsActivity`: the row renders dimmed and
tapping it opens a dialog with the reason (from
`FFmpegEngine.gateReason(key)` for engine limits, else
`LOCAL_GATE_REASONS` in `SettingsActivity`) instead of silently
persisting. Rows below marked "Gated (fix pass)" are those nodes.
Nodes wired for real in the fix pass: `audio_focus` (bridged to
`PSet.playAlone`), `scroll_down_to_last_media`,
`selection_mode`, `quit_button`. Five more nodes were ungated
after Monitoring's fix-pass spot-check (finding F1) once their
consumers were verified live: `screen_lock_mode` and
`controls.lock_mode` (bridged to `PSet.lockMode`, read by
`LockGesturePolicy`), and `honour_headset_hook_multi_press`,
`prevnext_to_rewff`, `toggle_on_media_play_button` (read via
`Store.boolSetting` by both of the player's media-key paths). `core_limit` and
`omx_audio_codecs` are real engine inputs (see Decoder table);
their final hop — the player passing the stored values into
`SwAudioSession.open(...)` — is PlayerActivity wiring.

## Player

| Key | Type | Default | Consumer |
|---|---|---|---|
| `resume_last` | enum resume\|startover\|ask | `ask` | Player (PSet-bridged `resumeMode`) — "Ask at Startup" prompt is shown by the player |
| `resume_only_first` | bool | false | Player (PSet-bridged) |
| `default_playback_speed` | int % (25–400) | 100 | Player (PSet-bridged) |
| `remember_selections` | bool | true | Player (PSet-bridged) |
| `back_to_list` | bool | false | Player (PSet-bridged) |
| `seek_previews` | bool | false | Player (PSet-bridged) |
| `seek_previews_net.2` | int 0/1/2 | 2 | Gated (fix pass) — previews are local-only in this build |
| `fast_seek` | bool | true | Player (PSet-bridged `fastSeeking`) |
| `audio_focus` | bool | true | Player (PSet-bridged `playAlone`) — bridged in the fix pass |
| `media_buttons` | bool | true | Player (PSet-bridged) |
| `honour_headset_hook_multi_press` | bool | true | Player — read via `Store.boolSetting` in both media-key paths (`handleMediaKey` gate + both `dispatchMediaAction` variants) feeding `MediaButtonLogic.keyAction` |
| `prevnext_to_rewff` | bool | false | Player — read via `Store.boolSetting` in both `dispatchMediaAction` variants feeding `MediaButtonLogic.keyAction` (Next/Prev → FF/REW remap) |
| `toggle_on_media_play_button` | bool | false | Player — read via `Store.boolSetting` in both `dispatchMediaAction` variants feeding `MediaButtonLogic.keyAction` |
| `smart_prev` | bool | true | Player (PSet-bridged) |
| `suppress_error_message` | bool | false | Player (PSet-bridged) |
| `use_custom_popwindow` | bool | true | Gated (fix pass) — custom PiP popup not implemented (PiP itself works via the manifest flag) |
| `sticky_video` | enum stop\|background\|pip | `pip` | Player (PSet-bridged `bgMode`) |
| `sticky_audio` | bool | true | Player (PSet-bridged) |
| `album_art` | bool | true | Gated (fix pass) — not implemented |
| `sticky_smooth_switch` | bool | false | Gated (fix pass) — not implemented |
| `video_zoom_delay` | int ms | 0 | Gated (fix pass) — not applied |
| `video_zoom_limited` | bool | false | Gated (fix pass) — not implemented |
| `button_backlight_off` | bool | false | Gated (fix pass) — not available |
| `loading_circle_animation` | bool | true | Player (PSet-bridged) |
| `soft_main_keys` | bool | false | Gated (fix pass) — not customised |
| `screen_lock_mode` | int 0/1/2 | 0 | Player (PSet-bridged `lockMode`) — consumed by `LockGesturePolicy.unlocks`/`hint` in the player's lock handling |
| `android_40_compatible_mode.2` | bool | false | Gated (fix pass) — meaningless at min API 24 |
| `double_tap_back_key` | bool | false | Player (PSet-bridged) |
| `quick_zoom` | bool | true | Player (PSet-bridged) |

### Player → Tuner → Screen

| Key | Type | Default | Consumer |
|---|---|---|---|
| `screen.orientation` | int 0–5 | 0 | Player (PSet-bridged to `orientationMode`: auto_land/auto/land/rev_land/system/video) |
| `screen.fullscreen` | int 0/1/2 | 0 | Player (PSet-bridged to `fullScreen`: auto/on/off) |
| `screen.soft_buttons` | int 0/1/2 | 2 | Gated (fix pass) — not customised |
| `screen.brightness_enabled` | bool | true | Gated (fix pass) — brightness gesture is always on |
| `screen.brightness` | int 0–100 | 50 | Gated (fix pass) — player restores the last swipe-set `Store.brightness` |
| `screen.elapsed_time` | bool | true | Player (PSet-bridged) |
| `status_text` | bool | true | Player (PSet-bridged `batteryClock`) |
| `screen.battery_clock_titlebar` | bool | false | Gated (fix pass) — placement fixed |
| `screen.show_remaining_time` | bool | false | Player (PSet-bridged) |
| `screen.status_bar` | bool | true | Gated (fix pass) — not customised |
| `screen.auto_hide_soft_buttons` | bool | true | Gated (fix pass) — not customised |
| `screen.keep_screen_on` | bool | true | Player (PSet-bridged) |
| `screen.pause_on_obstructed` | bool | false | Gated (fix pass) — not implemented |
| `screen.show_interface_startup` | bool | true | Gated (fix pass) — interface always shows |
| `screen.place_at_bottom` | bool | false | Gated (fix pass) — placement fixed |
| `screen.rotation_button` | bool | true | Player (PSet-bridged) |
| `screen.hide_download_button` | bool | true | Gated (fix pass) — no download button exists offline |
| `screen.corner_offset_enabled` | bool | false | Gated (fix pass) — not implemented |
| `screen.corner_offset` | int 0–100 | 0 | Gated (fix pass) — not implemented |
| `screen.osd_text_color` | int ARGB | white | Gated (fix pass) — fixed |
| `screen.osd_bg_enabled` | bool | false | Gated (fix pass) — fixed |
| `screen.osd_bg_color` | int ARGB | black | Gated (fix pass) — fixed |

### Player → Tuner → Controls / Navigation / Style

| Key | Type | Default | Consumer |
|---|---|---|---|
| `controls.touch_action` | int 0–3 | 0 | Player (PSet-bridged) |
| `controls.key_updown_action` | int 0–2 | 0 | Gated (fix pass) — volume keys not remapped |
| `controls.wheel_action` | int 0–2 | 0 | Gated (fix pass) — no wheel handling |
| `controls.lock_mode` | int 0/1/2 | 0 | Player (PSet-bridged `lockMode`) — consumed by `LockGesturePolicy.unlocks`/`hint` in the player's lock handling |
| `controls.lock_targets` | set {Input, Screen Rotation} | both | Player (PSet-bridged to single `lockTarget`: rotation only when it is the sole target) |
| `gesture.seek_position` | bool | true | Player (PSet-bridged) |
| `gesture.zoom_and_pan` | bool | true | Player (PSet-bridged) |
| `gesture.video_zoom` | bool | true | Player (PSet-bridged) |
| `gesture.video_pan` | bool | true | Player (PSet-bridged) |
| `gesture.volume` | bool | true | Player (PSet-bridged) |
| `gesture.brightness` | bool | true | Player (PSet-bridged) |
| `gesture.double_tap_play_pause` | bool | false | Player (PSet-bridged) |
| `gesture.double_tap_zoom` | bool | false | Player (PSet-bridged) |
| `gesture.double_tap_ffrw` | bool | true | Player (PSet-bridged) |
| `gesture.long_press_speed_ff` | bool | true | Player (PSet-bridged) |
| `gesture.playback_speed` | bool | false | Player (PSet-bridged `gTwoFingerSpeed`) |
| `gesture.subtitle_scroll` | bool | false | Player (PSet-bridged) |
| `gesture.subtitle_up_down` | bool | false | Player (PSet-bridged) |
| `gesture.subtitle_zoom` | bool | false | Gated (fix pass) — bridged to `gSubZoom`, which has no gesture consumer |
| `controls.interface_auto_hide` | bool | true | Gated (fix pass) — auto-hide cannot be disabled; the delay below is live |
| `controls.interface_auto_hide_delay` | int sec | 3 | Player (PSet-bridged to `autoHideMs`) |
| `controls.show_interface_when_locked` | bool | true | Player (PSet-bridged) |
| `controls.quick_shortcuts` | list of shortcut ids | all 15 (MX order) | Player (PSet-bridged to `railShortcuts`, comma-joined) |
| `nav.seek_speed` | int % | 100 | Player (PSet-bridged to float `seekSpeed`) |
| `nav.forward_backward_button` | bool | true | Player (PSet-bridged) |
| `nav.move_interval_sec` | int sec | 10 | Player (PSet-bridged) |
| `nav.prev_next_button` | bool | true | Player (PSet-bridged) |
| `nav.display_current_position` | bool | true | Player (PSet-bridged) |
| `playback.theme` | theme value (22) | `black_blueAccent` | Player (PSet-bridged `playbackTheme`) |
| `player_style` | int 0/1 | 0 | Gated (fix pass) — one style implemented |
| `progress_bar_style` | int 0/1 | 0 | Gated (fix pass) — one style implemented |

## Decoder

| Key | Type | Default | Consumer |
|---|---|---|---|
| `decoder_mode` | enum default\|hw\|hwplus\|sw | `default` | Player/Engine (PSet-bridged to `decoderDefault`: 0/1/4/2 — MX's decoder ints; also the "Play using…" intent extra `decoder`) |
| `omxdecoder.2` | bool | true | Gated (fix pass) — decoder enable switches not wired; `decoder_mode` is the live control |
| `omxdecoder_local` | bool | true | Gated (fix pass) — same |
| `omxdecoder_net` | bool | false | Gated (fix pass) — same (and no network playback exists — CUT) |
| `try_hw_if_omx_fails.2` | bool | true | Gated (fix pass) — fallback to HW is automatic |
| `try_omx_if_hw_fails` | bool | false | Gated (fix pass) — same |
| `swdecoder_local` | bool | false | Gated (fix pass) — same |
| `swdecoder_net` | bool | false | Gated (fix pass) — same |
| `omxdecoder.alt` | bool | false | Gated (fix pass) — same |
| `correct_hw_aspect_ratio` | bool | true | Gated (fix pass) — handled by the system render path |
| `hw_audio_track_selectable` | bool | false | Gated (fix pass) — tracks are chosen in the player's Audio Track menu |
| `omx_audio_with_sw_video` | bool | true | Gated (fix pass) — no SW video in this build |
| `default_swaudio` | bool | false | Gated (fix pass) — SW audio engages via the decoder choice |
| `swaudio_local` | bool | false | Gated (fix pass) — same |
| `swaudio_net` | bool | false | Gated (fix pass) — same |
| `calibrate_hw_play_position` | float sec | 0 | Player (PSet-bridged to ms `calibratePosMs`) |
| `core_limit` | int (0 = default) | 0 | Engine — REAL (fix pass): passed as `threads` to `SwAudioSession.open(...)`, applied in the bridge as avcodec `thread_count` before open; `SwAudioSession.decoderThreads` reports the effective count |
| `color_format` | enum rgb565\|rgbx8888\|yv12 | `rgb565` | Engine capability-gated: `FFmpegEngine.supportsColorFormatSelection()` = false (video surface format; engine is audio-only) — the settings UI gates the node with `FFmpegEngine.gateReason("color_format")` (fix pass) |
| `use_speedup_tricks` | bool | false | Player (PSet-bridged); engine capability-gated: `FFmpegEngine.supportsSpeedupTricks()` = false (SW-video-decoder option; engine is audio-only) — settings node gated with `FFmpegEngine.gateReason("use_speedup_tricks")` (fix pass) |
| `default_deinterlacer` | int 0/1/2 | 0 | Player (PSet-bridged); engine capability-gated: `FFmpegEngine.supportsDeinterlace()` = false (video filter; engine decodes no video) — settings node gated with `FFmpegEngine.gateReason("default_deinterlacer")` (fix pass) |
| `omx_video_codecs.3` | set (13 codecs) | all | Engine capability-gated: video stays on the hardware decoder, the engine decodes no video — settings node gated with `FFmpegEngine.gateReason("omx_video_codecs.3")` (fix pass) |
| `omx_audio_codecs` | set (18 codecs) | all | Engine — REAL (fix pass): pass the enabled set (as raw FFmpeg codec names) as `allowedAudioCodecs` to `SwAudioSession.open(...)`; a stream whose codec is disabled makes open() fail so the player falls back per try-HW rules |
| `custom_codec` | path | "" | Not loadable in this build — the codec is our bundled FFmpeg 7.1.1 build; the settings node opens an explanatory dialog (fix pass). MX's "Download custom codec" is an internet feature (CUT) |

## Audio

| Key | Type | Default | Consumer |
|---|---|---|---|
| `audio_player` | bool | false | Gated (fix pass) — no separate audio-player mode; music uses the standard player |
| `audio_device` | enum auto\|opensles\|audiotrack | `auto` | Engine capability-gated: output is always Android AudioTrack; `FFmpegEngine.supportsAudioOutputSelection()` = false (OpenSL ES unused) — settings node gated with `FFmpegEngine.gateReason("audio_device")` (fix pass) |
| `volume_boost` | bool | false | Player (PSet-bridged `volumeBoostGate`) |
| `sync_system_volume` | bool | true | Player (PSet-bridged) |
| `headset_show_system_volume_ui` | bool | true | Gated (fix pass) — not customised |
| `pause_on_headset_disconnected` | bool | true | Player (PSet-bridged) |
| `audio_fade_in_on_start` | bool | true | Player (PSet-bridged) |
| `audio_fade_in_on_seek` | bool | true | Player (PSet-bridged) |
| `audio_language` | locale tag ("" = none) | "" | Player (PSet-bridged `prefAudioLang`) |
| `audio_delay` | float sec | 0 | Player (PSet-bridged to ms); Engine — REAL (fix pass): the player must hand the summed delay to `SwAudioSession.setDelayMs(ms)` (positive = audio lags video). Once it does, EngineBridge must stop folding the delay into `position()` or it applies twice |
| `bluetooth_audio_delay` | float sec | 0 | Player (PSet-bridged to ms); Engine — same `setDelayMs` path as `audio_delay` (fix pass) |
| `prefer_audio_passthrough_mode` | bool | true | Player (PSet-bridged); engine capability-gated: `FFmpegEngine.supportsPassthrough()` = false (engine decodes to PCM; no bitstream output) — settings node gated with `FFmpegEngine.gateReason("prefer_audio_passthrough_mode")` (fix pass) |
| `audio_effects_enabled` | bool | false | Library equalizer gate + Player (PSet-bridged `equalizerGate`) |

## Subtitle

| Key | Type | Default | Consumer |
|---|---|---|---|
| `subtitle_folder` | uri/path | "" | Player (PSet-bridged `subFolder`) |
| `subtitle_charset` | charset ("" = Auto) | "" | Player (PSet-bridged `subEncoding`, "" ↔ "Auto") |
| `subtitle_language` | locale tag | "" | Player (PSet-bridged) |
| `subtitle_show_hw` | bool | false | Player (PSet-bridged `subHw`) |
| `subtitle_hw_accel` | bool | true | Gated (fix pass) — HW-accelerated subtitle rendering not implemented (also on the General screen, MX parity) |
| `subtitle_default_sync` | float sec | 0 | Player (PSet-bridged to ms) |
| `italic_tag` | bool | false | Player (PSet-bridged) |
| `force_ltr` | bool | true | Player (PSet-bridged) |
| `tune_subtitle_typeface` | font name ("" = Default) | "" | Player (PSet-bridged `subFont`, "" ↔ "Default") |
| `typeface_dir` | uri/path | "" | Player (PSet-bridged `subFontFolder`) + font picker |
| `tune_subtitle_scale` | int % (25–400) | 100 | Player (PSet-bridged `subScalePct`); also syncs `Store.subtitleTextSizeSp` = 18sp × scale |
| `tune_subtitle_text_color` | int ARGB | white | Player (PSet-bridged) |
| `tune_subtitle_border_color` | int ARGB | black | Gated (fix pass) — border colour not adjustable |
| `tune_subtitle_shadow` | bool | true | Player (PSet-bridged) |
| `tune_subtitle_bold` | bool | false | Player (PSet-bridged) |
| `tune_subtitle_improve_stroke_rendering` | bool | false | Player (PSet-bridged) |
| `tune_subtitle_alignment` | int 0/1/2 | 1 | Player (PSet-bridged to left/center/right) |
| `tune_subtitle_bottom_padding` | int dp (0–100) | 0 | Player (PSet-bridged `subBottomMarginDp`; PSet's own default is 24dp — the bridge reads PSet, so the screen shows the player's effective value) |
| `tune_subtitle_bg_enabled` | bool | false | Player (PSet-bridged) |
| `tune_subtitle_bg_color` | int ARGB | black | Player (PSet-bridged; PSet default is 50% black) |
| `Store.subtitleTextSizeSp` | float sp (10–40) | 18 | v0.1 property (kept); Subtitle screen "Text size" node |

## List

| Key | Type | Default | Consumer |
|---|---|---|---|
| `list.theme` | theme value (25) | `white` | Library + all surfaces (`Settings.themeColors`) |
| `mark_last_played_media_for_each_folders` | bool | true | Library (folder-row marker) |
| `scroll_down_to_last_media` | bool | false | Library (fix pass: `refreshDisplay` scrolls the list/grid to the last-played row, or its folder row) |
| `selection_mode` | bool | false | Library (fix pass: when on, tapping a thumbnail enters selection mode; long-press + Select also work) |
| `list.floating_action_button` | bool | true | Library (play-last FAB) |
| `new_tagged_period` | int days | 7 | Library (NEW badge via `Library.isNewTag`) |
| `scan_folders` | set of dir paths (empty = all) | [] | Library (`Library.filterByScanSettings`) |
| `scan_extensions` | set (empty = all) | [] | Library (same) |
| `respect_nomedia` | bool | true | Library (`Library.scan`) |
| `show_hidden` | bool | false | Library (dotfiles + app-hidden visibility) |
| `list.refresh_methods` | set {button, swipe} | both | Library (overflow Refresh item / pull-to-refresh) |
| `list_fields` | set of field ids | duration, size, resolution | Library row metadata line |
| `list.layout` | list\|grid (internal) | list | Library |
| `list.view_mode` | folders\|all\|files (internal) | folders | Library |
| `list.sort_field` | field id (internal) | default | Library |
| `list.sort_ascending` | bool (internal) | true | Library |

## General

| Key | Type | Default | Consumer |
|---|---|---|---|
| `user_locale` | locale tag ("" = system) | "" | Gated (fix pass) — the app's UI strings are English-only in this build, so the node opens the honest dialog instead of persisting |
| `quit_button` | bool | false | Library (fix pass: the Quit overflow entry shows only when this is on) |
| `tv_mode` | bool | false | Gated (fix pass) — TV mode not implemented |
| `allow_edits` | bool | true | Library (rename/delete gate) |
| `delete_subtitle_files_together` | bool | true | Library (`FileOps.moveToRecycle`) |
| `cache_thumbnail` | bool | true | Library thumbnail cache |

Actions (not keys): Clear history → `Store.clearHistoryData()`;
Clear thumbnail cache → deletes `cacheDir`; Clear font cache →
deletes `filesDir/fonts`; Export/Import settings → JSON via the
system document picker. Fix-pass format: `{"settings": {...},
"activity": {...}, "uiPlaylists": [...]}` — `settings` holds the
**effective** value of every key (bridged PSet values included,
fixing the stale-export wart), `activity` is
`Store.activityJson()` (resume positions, finished flags,
last-played timestamps, search history, Store playlists, hidden
files, favourites, per-file selections, play queue) and
`uiPlaylists` is `PSet.customPlaylists`. Import still accepts the
legacy flat v0.2 export shape. Export/Import materials → JSON of
`subtitle_folder`, `typeface_dir`, `custom_codec`, `scan_folders`
(now also read as effective values); Reset settings →
`Store.resetSettings()` + bridge defaults re-applied to PSet. CUT
(internet): "Play media links", "Http User-Agent", "Check for
updates", "Download custom codec".

## Internal state (ours, not MX preferences)

| Key | Meaning |
|---|---|
| `guide_shown` | first-run gesture guide dismissed |
| `last_played_path` | most recent played path (play-last button/FAB, folder markers) — written by `Store.recordPlayed` |
| `last_music_path/title/artist/queue` | most recent music playback (fix pass) — the music home-screen widgets' data source, written by `MusicActivity` on track tap |
| `Store.brightness`, `Store.subtitleTextSizeSp`, `Store.lastFolder` | v0.1 properties, unchanged |
