# PLAYER-SERVICE-NEEDS.md — manifest entries required by the v0.2 fix pass

**From:** Shadow Two (player half) · **For:** Jervis to reconcile into
`AndroidManifest.xml` (manifest is Shadow One's surface; PlayerActivity.kt
was NOT blocked on this — the code is written and degrades gracefully
until these lines land).

## What the code does without these lines

`PlaybackService` (declared in `PlayerActivity.kt`, MX PlayService model)
is fully implemented: foreground service + framework `MediaSession`,
notification with working Previous/Play-Pause/Next actions, headset/BT
media-button routing. `PlayerActivity.startBackgroundPlay()` checks
`PlaybackService.isDeclared()` at runtime:

- **Service declared** → real path: service starts, process is kept
  alive, notification + session are live.
- **Not declared** → fallback: the v0.2 in-activity background play +
  plain notification (process death still ends playback — unchanged
  from the monitored build).

So the app builds and behaves exactly as before until the lines below
are added; adding them turns the real path on. No other code change
is needed.

## Lines to add — permissions (inside `<manifest>`, with the others)

```xml
<!-- Foreground playback service (MX PlayService model). Still no
     INTERNET, no location. FOREGROUND_SERVICE is required since
     API 28 to start a foreground service at all;
     FOREGROUND_SERVICE_MEDIA_PLAYBACK is required on API 34
     (targetSdk 34) for the mediaPlayback service type below.
     POST_NOTIFICATIONS is the API 33+ runtime grant that makes the
     service's notification visible; PlayerActivity requests it when
     background play starts (without the grant the service still
     runs — Android surfaces it via the task manager instead). -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

## Line to add — service (inside `<application>`)

```xml
<!-- Background playback service: keeps the process alive while
     PlayerActivity plays in the background; owns the playback
     notification + MediaSession. Declared in PlayerActivity.kt. -->
<service
    android:name=".PlaybackService"
    android:exported="false"
    android:foregroundServiceType="mediaPlayback" />
```

## Notes

- `android:foregroundServiceType="mediaPlayback"` is mandatory at
  targetSdk 34 — `startForeground()` throws without it.
- `exported="false"` is deliberate: the service's actions are fired
  only by its own notification PendingIntents and by PlayerActivity.
- Not requested here (separate finding, P1-1 in MONITOR-REPORT-V02):
  `android:supportsPictureInPicture="true"` on `.PlayerActivity` —
  that's the PiP fix, owned outside this note.
