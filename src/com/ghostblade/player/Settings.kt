package com.ghostblade.player

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

/** MX-copy settings registry (v0.2): every settings key, its raw
 *  string default, and the value tables mined from MX Player 2.12.4
 *  (see carbon/MX-COPY-SPEC.md §2.6–§2.8 and §6.2, and
 *  SETTINGS-KEYS.md for the full documentation).
 *
 *  Keys reuse MX's own preference keys wherever the mining resolved
 *  them (e.g. `resume_last`, `list.theme`, `omxdecoder_local`), so
 *  the persisted state reads like MX's. Values are stored as raw
 *  strings in [Store]'s settings map: booleans "true"/"false",
 *  numbers in plain decimal, string sets/lists as JSON arrays.
 *
 *  Defaults marked in SETTINGS-KEYS.md as MX-extracted come from the
 *  preference XML; the few MX leaves to code (spinner indices, tuner
 *  seekbars) use the build choices recorded there.
 */
object Settings {

    /* ---------------- Player → Interface ---------------- */
    const val PLAYBACK_THEME = "playback.theme"            // default black_blueAccent
    const val PLAYER_STYLE = "player_style"                // int, tuner Style
    const val PROGRESS_BAR_STYLE = "progress_bar_style"    // int 0=Flat 1=Material

    /* Player → Screen tuner */
    const val SCREEN_ORIENTATION = "screen.orientation"    // int index
    const val SCREEN_FULLSCREEN = "screen.fullscreen"      // int index
    const val SCREEN_SOFT_BUTTONS = "screen.soft_buttons"  // int index
    const val SCREEN_BRIGHTNESS_ENABLED = "screen.brightness_enabled"
    const val SCREEN_BRIGHTNESS = "screen.brightness"      // int 0..100
    const val SCREEN_ELAPSED_TIME = "screen.elapsed_time"
    const val SCREEN_BATTERY_CLOCK = "status_text"
    const val SCREEN_CORNER_OFFSET_ENABLED = "screen.corner_offset_enabled"
    const val SCREEN_CORNER_OFFSET = "screen.corner_offset" // int 0..100
    const val SCREEN_OSD_TEXT_COLOR = "screen.osd_text_color" // int ARGB
    const val SCREEN_OSD_BG_ENABLED = "screen.osd_bg_enabled"
    const val SCREEN_OSD_BG_COLOR = "screen.osd_bg_color"  // int ARGB
    const val SCREEN_PLACE_AT_BOTTOM = "screen.place_at_bottom"
    const val SCREEN_ROTATION_BUTTON = "screen.rotation_button"
    const val SCREEN_HIDE_DOWNLOAD_BUTTON = "screen.hide_download_button"
    const val SCREEN_BATTERY_CLOCK_TITLEBAR = "screen.battery_clock_titlebar"
    const val SCREEN_SHOW_REMAINING_TIME = "screen.show_remaining_time"
    const val SCREEN_STATUS_BAR = "screen.status_bar"
    const val SCREEN_AUTO_HIDE_SOFT_BUTTONS = "screen.auto_hide_soft_buttons"
    const val SCREEN_KEEP_SCREEN_ON = "screen.keep_screen_on"
    const val SCREEN_PAUSE_ON_OBSTRUCTED = "screen.pause_on_obstructed"
    const val SCREEN_SHOW_INTERFACE_STARTUP = "screen.show_interface_startup"

    /* Player → Controls tuner */
    const val CONTROLS_TOUCH_ACTION = "controls.touch_action" // int index
    const val CONTROLS_KEY_UPDOWN_ACTION = "controls.key_updown_action"
    const val CONTROLS_WHEEL_ACTION = "controls.wheel_action"
    const val CONTROLS_LOCK_MODE = "controls.lock_mode"       // int index
    const val CONTROLS_LOCK_TARGETS = "controls.lock_targets" // string set
    const val GESTURE_SEEK_POSITION = "gesture.seek_position"
    const val GESTURE_ZOOM_AND_PAN = "gesture.zoom_and_pan"
    const val GESTURE_VIDEO_ZOOM = "gesture.video_zoom"
    const val GESTURE_VIDEO_PAN = "gesture.video_pan"
    const val GESTURE_VOLUME = "gesture.volume"
    const val GESTURE_BRIGHTNESS = "gesture.brightness"
    const val GESTURE_DOUBLE_TAP_PLAY_PAUSE = "gesture.double_tap_play_pause"
    const val GESTURE_DOUBLE_TAP_ZOOM = "gesture.double_tap_zoom"
    const val GESTURE_DOUBLE_TAP_FFRW = "gesture.double_tap_ffrw"
    const val GESTURE_LONG_PRESS_SPEED_FF = "gesture.long_press_speed_ff"
    const val GESTURE_PLAYBACK_SPEED = "gesture.playback_speed"
    const val GESTURE_SUBTITLE_SCROLL = "gesture.subtitle_scroll"
    const val GESTURE_SUBTITLE_UP_DOWN = "gesture.subtitle_up_down"
    const val GESTURE_SUBTITLE_ZOOM = "gesture.subtitle_zoom"
    const val CONTROLS_QUICK_SHORTCUTS = "controls.quick_shortcuts" // string list
    const val CONTROLS_INTERFACE_AUTO_HIDE = "controls.interface_auto_hide"
    const val CONTROLS_INTERFACE_AUTO_HIDE_DELAY = "controls.interface_auto_hide_delay" // sec
    const val CONTROLS_SHOW_INTERFACE_WHEN_LOCKED = "controls.show_interface_when_locked"

    /* Player → Navigation tuner */
    const val NAV_SEEK_SPEED = "nav.seek_speed"              // int percent
    const val NAV_FORWARD_BACKWARD_BUTTON = "nav.forward_backward_button"
    const val NAV_MOVE_INTERVAL_SEC = "nav.move_interval_sec" // int seconds
    const val NAV_PREV_NEXT_BUTTON = "nav.prev_next_button"
    const val NAV_DISPLAY_CURRENT_POSITION = "nav.display_current_position"

    const val DOUBLE_TAP_BACK = "double_tap_back_key"
    const val QUICK_ZOOM = "quick_zoom"

    /* Player → Playback */
    const val RESUME = "resume_last"                         // resume|startover|ask
    const val RESUME_ONLY_FIRST = "resume_only_first"
    const val DEFAULT_PLAYBACK_SPEED = "default_playback_speed" // int 25..400
    const val REMEMBER_SELECTIONS = "remember_selections"
    const val BACK_TO_LIST = "back_to_list"
    const val SEEK_PREVIEWS = "seek_previews"
    const val SEEK_PREVIEWS_NET = "seek_previews_net.2"      // int 0/1/2
    const val FAST_SEEK = "fast_seek"
    const val AUDIO_FOCUS = "audio_focus"
    const val MEDIA_BUTTONS = "media_buttons"
    const val HEADSET_MULTI_PRESS = "honour_headset_hook_multi_press"
    const val PREVNEXT_TO_REWFF = "prevnext_to_rewff"
    const val TOGGLE_ON_MEDIA_PLAY_BUTTON = "toggle_on_media_play_button"
    const val SMART_PREV = "smart_prev"
    const val SUPPRESS_ERROR_MESSAGE = "suppress_error_message"
    const val USE_CUSTOM_POPWINDOW = "use_custom_popwindow"

    /* Player → Background Play */
    const val STICKY_VIDEO = "sticky_video"                  // stop|background|pip
    const val STICKY_AUDIO = "sticky_audio"
    const val ALBUM_ART = "album_art"
    const val STICKY_SMOOTH_SWITCH = "sticky_smooth_switch"

    /* Player → Miscellaneous */
    const val VIDEO_ZOOM_DELAY = "video_zoom_delay"          // int ms
    const val VIDEO_ZOOM_LIMITED = "video_zoom_limited"
    const val BUTTON_BACKLIGHT_OFF = "button_backlight_off"
    const val LOADING_CIRCLE_ANIMATION = "loading_circle_animation"
    const val SOFT_MAIN_KEYS = "soft_main_keys"
    const val SCREEN_LOCK_MODE = "screen_lock_mode"          // int 0/1/2
    const val ANDROID_40_COMPAT = "android_40_compatible_mode.2"

    /* ---------------- Decoder ---------------- */
    const val OMX_DECODER = "omxdecoder.2"
    const val OMX_DECODER_LOCAL = "omxdecoder_local"
    const val OMX_DECODER_NET = "omxdecoder_net"
    const val TRY_HW_IF_OMX_FAILS = "try_hw_if_omx_fails.2"
    const val TRY_OMX_IF_HW_FAILS = "try_omx_if_hw_fails"
    const val OMX_VIDEO_CODECS = "omx_video_codecs.3"        // string set
    const val OMX_AUDIO_CODECS = "omx_audio_codecs"          // string set
    const val OMX_AUDIO_WITH_SW_VIDEO = "omx_audio_with_sw_video"
    const val CORRECT_HW_ASPECT_RATIO = "correct_hw_aspect_ratio"
    const val CALIBRATE_HW_PLAY_POSITION = "calibrate_hw_play_position" // float sec
    const val HW_AUDIO_TRACK_SELECTABLE = "hw_audio_track_selectable"
    const val OMX_DECODER_ALT = "omxdecoder.alt"
    const val SW_DECODER_LOCAL = "swdecoder_local"
    const val SW_DECODER_NET = "swdecoder_net"
    const val SW_AUDIO = "default_swaudio"
    const val SW_AUDIO_LOCAL = "swaudio_local"
    const val SW_AUDIO_NET = "swaudio_net"
    const val CORE_LIMIT = "core_limit"                      // int, 0 = default
    const val COLOR_FORMAT = "color_format"                  // rgb565|rgbx8888|yv12
    const val USE_SPEEDUP_TRICKS = "use_speedup_tricks"
    const val DEFAULT_DEINTERLACER = "default_deinterlacer"  // int 0/1/2
    const val CUSTOM_CODEC = "custom_codec"                  // local file path
    /** Convenience default-decoder key consumed by the player/engine:
     *  values default|hw|hwplus|sw. Written by "Play using…" (as an
     *  intent extra, not this key), the in-player decoder dialog
     *  (Shadow Two) and read as the fallback default. */
    const val DECODER_MODE = "decoder_mode"

    /* ---------------- Subtitle ---------------- */
    const val SUBTITLE_FOLDER = "subtitle_folder"
    const val SUBTITLE_CHARSET = "subtitle_charset"          // "" = Auto
    const val SUBTITLE_LANGUAGE = "subtitle_language"        // "" = none
    const val SUBTITLE_SHOW_HW = "subtitle_show_hw"
    const val SUBTITLE_DEFAULT_SYNC = "subtitle_default_sync" // float sec
    const val SUBTITLE_TYPEFACE = "tune_subtitle_typeface"
    const val SUBTITLE_SCALE = "tune_subtitle_scale"         // int percent
    const val SUBTITLE_TEXT_COLOR = "tune_subtitle_text_color" // int ARGB
    const val SUBTITLE_BORDER_COLOR = "tune_subtitle_border_color" // int ARGB
    const val SUBTITLE_SHADOW = "tune_subtitle_shadow"
    const val SUBTITLE_BOLD = "tune_subtitle_bold"
    const val SUBTITLE_IMPROVE_STROKE = "tune_subtitle_improve_stroke_rendering"
    const val SUBTITLE_ALIGNMENT = "tune_subtitle_alignment" // int 0/1/2
    const val SUBTITLE_BOTTOM_PADDING = "tune_subtitle_bottom_padding" // int dp
    const val SUBTITLE_BG_ENABLED = "tune_subtitle_bg_enabled"
    const val SUBTITLE_BG_COLOR = "tune_subtitle_bg_color"   // int ARGB
    const val TYPEFACE_DIR = "typeface_dir"
    const val SUBTITLE_HW_ACCEL = "subtitle_hw_accel"        // shared w/ General
    const val ITALIC_TAG = "italic_tag"
    const val FORCE_LTR = "force_ltr"

    /* ---------------- Audio ---------------- */
    const val AUDIO_PLAYER = "audio_player"
    const val AUDIO_DEVICE = "audio_device"                  // auto|opensles|audiotrack
    const val VOLUME_BOOST = "volume_boost"
    const val SYNC_SYSTEM_VOLUME = "sync_system_volume"
    const val HEADSET_SHOW_SYSTEM_VOLUME_UI = "headset_show_system_volume_ui"
    const val PAUSE_ON_HEADSET_DISCONNECTED = "pause_on_headset_disconnected"
    const val AUDIO_FADE_IN_ON_START = "audio_fade_in_on_start"
    const val AUDIO_FADE_IN_ON_SEEK = "audio_fade_in_on_seek"
    const val AUDIO_LANGUAGE = "audio_language"
    const val AUDIO_DELAY = "audio_delay"                    // float sec
    const val BLUETOOTH_AUDIO_DELAY = "bluetooth_audio_delay" // float sec
    const val PREFER_AUDIO_PASSTHROUGH = "prefer_audio_passthrough_mode"
    const val AUDIO_EFFECTS_ENABLED = "audio_effects_enabled"

    /* ---------------- List ---------------- */
    const val LIST_THEME = "list.theme"
    const val MARK_LAST_PLAYED = "mark_last_played_media_for_each_folders"
    const val SCROLL_TO_LAST_MEDIA = "scroll_down_to_last_media"
    const val SELECTION_MODE = "selection_mode"
    const val LIST_FLOATING_BUTTON = "list.floating_action_button"
    const val NEW_TAGGED_PERIOD = "new_tagged_period"        // int days
    const val SCAN_FOLDERS = "scan_folders"                  // string set of dir paths; empty = all
    const val SCAN_EXTENSIONS = "scan_extensions"            // string set; empty = all known
    const val RESPECT_NOMEDIA = "respect_nomedia"
    const val SHOW_HIDDEN = "show_hidden"
    const val REFRESH_METHODS = "list.refresh_methods"       // string set {button, swipe}
    const val LIST_FIELDS = "list_fields"                    // string set of field ids
    /** Internal library UI state (ours, not MX preferences). */
    const val LIST_LAYOUT = "list.layout"                    // list|grid
    const val LIST_VIEW_MODE = "list.view_mode"              // folders|all|files
    const val LIST_SORT_FIELD = "list.sort_field"            // default|title|date|length|size|resolution|framerate|status|type|location
    const val LIST_SORT_ASCENDING = "list.sort_ascending"

    /* ---------------- General ---------------- */
    const val USER_LOCALE = "user_locale"                    // "" = system
    const val QUIT_BUTTON = "quit_button"
    const val TV_MODE = "tv_mode"
    const val ALLOW_EDITS = "allow_edits"
    const val DELETE_SUBTITLE_TOGETHER = "delete_subtitle_files_together"
    const val CACHE_THUMBNAIL = "cache_thumbnail"

    /* ---------------- Internal state (ours) ---------------- */
    const val GUIDE_SHOWN = "guide_shown"
    const val LAST_PLAYED_PATH = "last_played_path"
    /** Music-widget state (spec #106): the last track started from
     *  the Music surface and its queue, so the home-screen widgets
     *  can show it and fire play/prev/next intents at the player. */
    const val LAST_MUSIC_PATH = "last_music_path"
    const val LAST_MUSIC_TITLE = "last_music_title"
    const val LAST_MUSIC_ARTIST = "last_music_artist"
    const val LAST_MUSIC_QUEUE = "last_music_queue"      // JSON array of paths

    /* ================= value tables (MX-extracted) ================= */

    val PLAYBACK_THEMES = listOf(
        "white", "black", "black_redAccent", "black_pinkAccent", "black_fl_pinkAccent",
        "black_orangeAccent", "black_yellowAccent", "black_brownAccent", "black_greenAccent",
        "black_blueAccent", "black_indigoAccent", "black_purpleAccent", "dark_gray",
        "dark_navy", "red", "pink", "fl_pink", "orange", "brown", "green", "indigo", "purple"
    )

    val LIST_THEMES = listOf(
        "system", "white", "black", "black_redAccent", "black_pinkAccent", "black_fl_pinkAccent",
        "black_orangeAccent", "black_yellowAccent", "black_brownAccent", "black_greenAccent",
        "black_blueAccent", "black_indigoAccent", "black_purpleAccent", "dark_gray",
        "dark_gray2", "dark_navy", "dark_navy2", "red", "pink", "fl_pink", "orange",
        "brown", "green", "blue", "indigo", "purple"
    )

    val OMX_VIDEO_CODEC_LIST = listOf(
        "H.263", "H.264", "H.264 10bits", "H.265", "H.265 10bits", "MPEG-1", "MPEG-2",
        "MPEG-4", "MJPEG", "VP8", "VP9", "WMV3", "VC-1"
    )

    val OMX_AUDIO_CODEC_LIST = listOf(
        "AAC", "ALAC", "AMR NB", "AMR WB", "FLAC", "G.711 A-law", "G.711 μ-law", "GSM",
        "MP1", "MP2", "MP3", "Opus", "PCM", "QCELP", "Vorbis", "AC3", "EAC3", "DTS"
    )

    /** Quick Shortcuts rail set (spec §2.2, array `list_shortcuts_eng`). */
    val SHORTCUTS: List<Pair<String, String>> = listOf(
        "screen_rotation" to "Screen Rotation",
        "playback_speed" to "Playback Speed",
        "sticky" to "Background Play",
        "loop" to "Loop",
        "mute" to "Mute",
        "shuffle" to "Shuffle",
        "equalizer" to "Equalizer",
        "audioEffect" to "Audio Effect",
        "sleep_timer" to "Sleep Timer",
        "repeat_ab" to "A - B Repeat",
        "night_mode" to "Night Mode",
        "customise_items" to "Customise Items",
        "screenshot" to "Screenshot",
        "mirror" to "Mirror",
        "vertical_flip" to "Vertical Flip"
    )

    val VIDEO_EXTENSIONS = listOf(
        "3gp", "asf", "avi", "f4v", "flv", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg",
        "m2ts", "ogv", "rm", "rmvb", "ts", "vob", "webm", "wmv"
    )

    /** Row metadata fields for the List "Fields" chooser. */
    val LIST_FIELD_IDS: List<Pair<String, String>> = listOf(
        "duration" to "Duration",
        "size" to "Size",
        "resolution" to "Resolution",
        "date" to "Date",
        "framerate" to "Frame rate",
        "path" to "Path"
    )

    val SUBTITLE_CHARSETS: List<Pair<String, String>> = listOf(
        "" to "Auto",
        "UTF-8" to "UTF-8",
        "UTF-16" to "UTF-16",
        "ISO-8859-1" to "Western European (ISO-8859-1)",
        "windows-1250" to "Central European (windows-1250)",
        "windows-1251" to "Cyrillic (windows-1251)",
        "windows-1252" to "Western European (windows-1252)",
        "Shift_JIS" to "Japanese (Shift_JIS)",
        "EUC-KR" to "Korean (EUC-KR)",
        "GB2312" to "Chinese Simplified (GB2312)",
        "Big5" to "Chinese Traditional (Big5)"
    )

    val AUDIO_DEVICES: List<Pair<String, String>> = listOf(
        "auto" to "Auto",
        "opensles" to "OpenSL ES",
        "audiotrack" to "AudioTrack"
    )

    val COLOR_FORMATS: List<Pair<String, String>> = listOf(
        "rgb565" to "RGB 16 bits per pixel",
        "rgbx8888" to "RGB 32 bits per pixel",
        "yv12" to "YUV image"
    )

    /** Named colours offered by the colour-picker nodes (OSD /
     *  subtitle). Values are ARGB ints as raw strings in the map. */
    val COLOR_CHOICES: List<Pair<String, Int>> = listOf(
        "White" to Color.WHITE,
        "Black" to Color.BLACK,
        "MX Blue" to 0xFF3C8CF0.toInt(),
        "Red" to 0xFFF2405D.toInt(),
        "Amber" to 0xFFFFA300.toInt(),
        "Yellow" to 0xFFFFF176.toInt(),
        "Green" to 0xFF4E802B.toInt(),
        "Gray" to 0xFF85929C.toInt()
    )

    /** Raw-string defaults for every settings key. This table is the
     *  single source of truth: [Store] falls back to it, the settings
     *  screens render from it, and the JVM tests pin it against the
     *  spec. String sets/lists are JSON arrays. */
    val DEFAULTS: Map<String, String> = mapOf(
        // Player / Interface
        PLAYBACK_THEME to "black_blueAccent",
        PLAYER_STYLE to "0",
        PROGRESS_BAR_STYLE to "0",
        SCREEN_ORIENTATION to "0",
        SCREEN_FULLSCREEN to "0",
        SCREEN_SOFT_BUTTONS to "2",
        SCREEN_BRIGHTNESS_ENABLED to "true",
        SCREEN_BRIGHTNESS to "50",
        SCREEN_ELAPSED_TIME to "true",
        SCREEN_BATTERY_CLOCK to "true",
        SCREEN_CORNER_OFFSET_ENABLED to "false",
        SCREEN_CORNER_OFFSET to "0",
        SCREEN_OSD_TEXT_COLOR to Color.WHITE.toString(),
        SCREEN_OSD_BG_ENABLED to "false",
        SCREEN_OSD_BG_COLOR to Color.BLACK.toString(),
        SCREEN_PLACE_AT_BOTTOM to "false",
        SCREEN_ROTATION_BUTTON to "true",
        SCREEN_HIDE_DOWNLOAD_BUTTON to "true",
        SCREEN_BATTERY_CLOCK_TITLEBAR to "false",
        SCREEN_SHOW_REMAINING_TIME to "false",
        SCREEN_STATUS_BAR to "true",
        SCREEN_AUTO_HIDE_SOFT_BUTTONS to "true",
        SCREEN_KEEP_SCREEN_ON to "true",
        SCREEN_PAUSE_ON_OBSTRUCTED to "false",
        SCREEN_SHOW_INTERFACE_STARTUP to "true",
        CONTROLS_TOUCH_ACTION to "0",
        CONTROLS_KEY_UPDOWN_ACTION to "0",
        CONTROLS_WHEEL_ACTION to "0",
        CONTROLS_LOCK_MODE to "0",
        CONTROLS_LOCK_TARGETS to "[\"Input\",\"Screen Rotation\"]",
        GESTURE_SEEK_POSITION to "true",
        GESTURE_ZOOM_AND_PAN to "true",
        GESTURE_VIDEO_ZOOM to "true",
        GESTURE_VIDEO_PAN to "true",
        GESTURE_VOLUME to "true",
        GESTURE_BRIGHTNESS to "true",
        GESTURE_DOUBLE_TAP_PLAY_PAUSE to "true",
        GESTURE_DOUBLE_TAP_ZOOM to "false",
        GESTURE_DOUBLE_TAP_FFRW to "true",
        GESTURE_LONG_PRESS_SPEED_FF to "true",
        GESTURE_PLAYBACK_SPEED to "false",
        GESTURE_SUBTITLE_SCROLL to "false",
        GESTURE_SUBTITLE_UP_DOWN to "false",
        GESTURE_SUBTITLE_ZOOM to "false",
        CONTROLS_QUICK_SHORTCUTS to SHORTCUTS.joinToString(",", "[", "]") { "\"${it.first}\"" },
        CONTROLS_INTERFACE_AUTO_HIDE to "true",
        CONTROLS_INTERFACE_AUTO_HIDE_DELAY to "3",
        CONTROLS_SHOW_INTERFACE_WHEN_LOCKED to "true",
        NAV_SEEK_SPEED to "100",
        NAV_FORWARD_BACKWARD_BUTTON to "true",
        NAV_MOVE_INTERVAL_SEC to "10",
        NAV_PREV_NEXT_BUTTON to "true",
        NAV_DISPLAY_CURRENT_POSITION to "true",
        DOUBLE_TAP_BACK to "false",
        QUICK_ZOOM to "true",
        // Player / Playback
        RESUME to "ask",
        RESUME_ONLY_FIRST to "false",
        DEFAULT_PLAYBACK_SPEED to "100",
        REMEMBER_SELECTIONS to "true",
        BACK_TO_LIST to "false",
        SEEK_PREVIEWS to "false",
        SEEK_PREVIEWS_NET to "2",
        FAST_SEEK to "true",
        AUDIO_FOCUS to "true",
        MEDIA_BUTTONS to "true",
        HEADSET_MULTI_PRESS to "true",
        PREVNEXT_TO_REWFF to "false",
        TOGGLE_ON_MEDIA_PLAY_BUTTON to "false",
        SMART_PREV to "true",
        SUPPRESS_ERROR_MESSAGE to "false",
        USE_CUSTOM_POPWINDOW to "true",
        // Player / Background
        STICKY_VIDEO to "pip",
        STICKY_AUDIO to "true",
        ALBUM_ART to "true",
        STICKY_SMOOTH_SWITCH to "false",
        // Player / Miscellaneous
        VIDEO_ZOOM_DELAY to "0",
        VIDEO_ZOOM_LIMITED to "false",
        BUTTON_BACKLIGHT_OFF to "false",
        LOADING_CIRCLE_ANIMATION to "true",
        SOFT_MAIN_KEYS to "false",
        SCREEN_LOCK_MODE to "0",
        ANDROID_40_COMPAT to "false",
        // Decoder
        OMX_DECODER to "true",
        OMX_DECODER_LOCAL to "true",
        OMX_DECODER_NET to "false",
        TRY_HW_IF_OMX_FAILS to "true",
        TRY_OMX_IF_HW_FAILS to "false",
        OMX_VIDEO_CODECS to OMX_VIDEO_CODEC_LIST.joinToString(",", "[", "]") { "\"$it\"" },
        OMX_AUDIO_CODECS to OMX_AUDIO_CODEC_LIST.joinToString(",", "[", "]") { "\"$it\"" },
        OMX_AUDIO_WITH_SW_VIDEO to "true",
        CORRECT_HW_ASPECT_RATIO to "true",
        CALIBRATE_HW_PLAY_POSITION to "0",
        HW_AUDIO_TRACK_SELECTABLE to "false",
        OMX_DECODER_ALT to "false",
        SW_DECODER_LOCAL to "false",
        SW_DECODER_NET to "false",
        SW_AUDIO to "false",
        SW_AUDIO_LOCAL to "false",
        SW_AUDIO_NET to "false",
        CORE_LIMIT to "0",
        COLOR_FORMAT to "rgb565",
        USE_SPEEDUP_TRICKS to "false",
        DEFAULT_DEINTERLACER to "0",
        CUSTOM_CODEC to "",
        DECODER_MODE to "default",
        // Subtitle
        SUBTITLE_FOLDER to "",
        SUBTITLE_CHARSET to "",
        SUBTITLE_LANGUAGE to "",
        SUBTITLE_SHOW_HW to "false",
        SUBTITLE_DEFAULT_SYNC to "0",
        SUBTITLE_TYPEFACE to "",
        SUBTITLE_SCALE to "100",
        SUBTITLE_TEXT_COLOR to Color.WHITE.toString(),
        SUBTITLE_BORDER_COLOR to Color.BLACK.toString(),
        SUBTITLE_SHADOW to "true",
        SUBTITLE_BOLD to "false",
        SUBTITLE_IMPROVE_STROKE to "false",
        SUBTITLE_ALIGNMENT to "1",
        SUBTITLE_BOTTOM_PADDING to "0",
        SUBTITLE_BG_ENABLED to "false",
        SUBTITLE_BG_COLOR to Color.BLACK.toString(),
        TYPEFACE_DIR to "",
        SUBTITLE_HW_ACCEL to "true",
        ITALIC_TAG to "false",
        FORCE_LTR to "true",
        // Audio
        AUDIO_PLAYER to "false",
        AUDIO_DEVICE to "auto",
        VOLUME_BOOST to "false",
        SYNC_SYSTEM_VOLUME to "true",
        HEADSET_SHOW_SYSTEM_VOLUME_UI to "true",
        PAUSE_ON_HEADSET_DISCONNECTED to "true",
        AUDIO_FADE_IN_ON_START to "true",
        AUDIO_FADE_IN_ON_SEEK to "true",
        AUDIO_LANGUAGE to "",
        AUDIO_DELAY to "0",
        BLUETOOTH_AUDIO_DELAY to "0",
        PREFER_AUDIO_PASSTHROUGH to "true",
        AUDIO_EFFECTS_ENABLED to "false",
        // List
        LIST_THEME to "system",
        MARK_LAST_PLAYED to "true",
        SCROLL_TO_LAST_MEDIA to "false",
        SELECTION_MODE to "false",
        LIST_FLOATING_BUTTON to "true",
        NEW_TAGGED_PERIOD to "7",
        SCAN_FOLDERS to "[]",
        SCAN_EXTENSIONS to "[]",
        RESPECT_NOMEDIA to "true",
        SHOW_HIDDEN to "false",
        REFRESH_METHODS to "[\"button\",\"swipe\"]",
        LIST_FIELDS to "[\"duration\",\"size\",\"resolution\"]",
        LIST_LAYOUT to "list",
        LIST_VIEW_MODE to "folders",
        LIST_SORT_FIELD to "default",
        LIST_SORT_ASCENDING to "true",
        // General
        USER_LOCALE to "",
        QUIT_BUTTON to "false",
        TV_MODE to "false",
        ALLOW_EDITS to "true",
        DELETE_SUBTITLE_TOGETHER to "true",
        CACHE_THUMBNAIL to "true",
        // Internal
        GUIDE_SHOWN to "false",
        LAST_PLAYED_PATH to "",
        LAST_MUSIC_PATH to "",
        LAST_MUSIC_TITLE to "",
        LAST_MUSIC_ARTIST to "",
        LAST_MUSIC_QUEUE to "[]"
    )

    /** Default for [key], or "" for unknown keys. */
    fun defaultOf(key: String): String = DEFAULTS[key] ?: ""

    /* ================= list themes ================= */

    /** Resolved colours for one list/playback theme value. Dark
     *  chrome set is MX-extracted (§1.1/§1.5); accent-variant hues
     *  come from the extracted user-theme table in §1.5. */
    data class ThemeColors(
        val pageBg: Int,
        val chrome: Int,
        val card: Int,
        val title: Int,
        val secondary: Int,
        val tertiary: Int,
        val accent: Int,
        val divider: Int,
        val dark: Boolean
    )

    private val MX_BLUE = 0xFF3C8CF0.toInt()
    private val DARK = ThemeColors(
        pageBg = 0xFF16202B.toInt(), chrome = 0xFF1C2939.toInt(), card = 0xFF26374C.toInt(),
        title = 0xFFDADDE4.toInt(), secondary = 0xFF85929C.toInt(), tertiary = 0xFF96A2BA.toInt(),
        accent = MX_BLUE, divider = 0x334E4E4E, dark = true
    )
    private val LIGHT = ThemeColors(
        pageBg = 0xFFFFFFFF.toInt(), chrome = MX_BLUE, card = 0xFFF5F5F5.toInt(),
        title = 0xFF35344C.toInt(), secondary = 0xFF505A78.toInt(), tertiary = 0xFF96A2BA.toInt(),
        accent = MX_BLUE, divider = 0x334E4E4E, dark = false
    )
    private val BLACK = ThemeColors(
        pageBg = 0xFF000000.toInt(), chrome = 0xFF111111.toInt(), card = 0xFF1A1A1A.toInt(),
        title = 0xFFFFFFFF.toInt(), secondary = 0xFF85929C.toInt(), tertiary = 0xFF96A2BA.toInt(),
        accent = MX_BLUE, divider = 0x334E4E4E, dark = true
    )

    fun themeColors(value: String): ThemeColors = when (value) {
        "white" -> LIGHT
        "black" -> BLACK
        "dark_navy" -> DARK.copy(pageBg = 0xFF1F292E.toInt(), chrome = 0xFF1F292E.toInt())
        "dark_navy2" -> DARK
        "dark_gray" -> BLACK.copy(accent = 0xFF81D4FA.toInt(), chrome = 0xFF212121.toInt(),
            pageBg = 0xFF212121.toInt(), card = 0xFF2A2A2A.toInt())
        "dark_gray2" -> BLACK.copy(accent = 0xFF81D4FA.toInt(), chrome = 0xFF282828.toInt(),
            pageBg = 0xFF1A1A1A.toInt(), card = 0xFF242424.toInt())
        "blue" -> LIGHT.copy(chrome = 0xFF4285F4.toInt(), accent = 0xFF4285F4.toInt())
        "red" -> BLACK.copy(accent = 0xFFDA4336.toInt(), chrome = 0xFFDA4336.toInt())
        "pink", "fl_pink" -> BLACK.copy(accent = 0xFFE91E63.toInt(), chrome = 0xFFE91E63.toInt())
        "orange" -> BLACK.copy(accent = 0xFFF3B60C.toInt(), chrome = 0xFFF3B60C.toInt())
        "brown" -> BLACK.copy(accent = 0xFF795548.toInt(), chrome = 0xFF795548.toInt())
        "green" -> BLACK.copy(accent = 0xFF009688.toInt(), chrome = 0xFF4E802B.toInt())
        "indigo" -> BLACK.copy(accent = 0xFF8C9EFF.toInt(), chrome = 0xFF3C4DAD.toInt())
        "purple" -> BLACK.copy(accent = 0xFF9933FF.toInt(), chrome = 0xFF3E2C5F.toInt())
        else -> {
            // black_<x>Accent family: black surfaces, coloured accent.
            val accent = when (value) {
                "black_redAccent" -> 0xFFF2405D.toInt()
                "black_pinkAccent", "black_fl_pinkAccent" -> 0xFFE91E63.toInt()
                "black_orangeAccent" -> 0xFFF3B60C.toInt()
                "black_yellowAccent" -> 0xFFFFF176.toInt()
                "black_brownAccent" -> 0xFF795548.toInt()
                "black_greenAccent" -> 0xFF009688.toInt()
                "black_blueAccent" -> MX_BLUE
                "black_indigoAccent" -> 0xFF8C9EFF.toInt()
                "black_purpleAccent" -> 0xFF9933FF.toInt()
                else -> MX_BLUE
            }
            BLACK.copy(accent = accent)
        }
    }

    /** v0.5.2: resolve the stored list theme the way MX does —
     *  the "system" value (the default since v0.5.2) follows the
     *  device's dark/light mode: black surfaces in dark mode, the
     *  white theme in light mode. Any explicitly chosen theme
     *  value resolves exactly as before. */
    fun currentThemeColors(context: Context): ThemeColors {
        val value = Store.stringSetting(LIST_THEME)
        if (value != "system") return themeColors(value)
        val night = context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK
        return if (night == Configuration.UI_MODE_NIGHT_YES) BLACK else LIGHT
    }
}
