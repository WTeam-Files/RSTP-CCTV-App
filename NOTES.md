
## Appliance changes (WTeam-Files fork)
- Defaults for a fresh install: 1280x720 (`AppPreferences.DEFAULT_WIDTH/HEIGHT`, service fields follow), `video_fps` 30 and audio OFF (already the defaults), snapshot active+idle interval 5000 ms (`CaptureProfile`), start-on-boot and auto-start-on-launch now default ON. Stored prefs still win and everything stays editable from the dashboard/app.
- `SnapshotBuffer.kt`: 3 h rolling JPEG buffer at `getExternalFilesDir/snapshot-buffer/YYYY-MM-DD/HH/MMSS.jpg`. Atomic tmp+rename, written on a single-thread executor, pruned by age (3 h) and count cap (4000) at most once a minute, skipped below 200 MB free, all failures swallowed. Capture loop now always runs while streaming so the buffer fills without viewers. Tests: `SnapshotBufferTest`.
- `BootReceiver`: default-on; after `startForegroundService` it also launches `MainActivity` when SYSTEM_ALERT_WINDOW is granted, because Android 11 denies camera access to a background FGS unless the app is visible.
- `CaptureProfileTest` updated for the new interval defaults.
- `ci.yml`: ubuntu-latest + `cctv-debug-apk` artifact.
- Fix: `CctvServerService.onStartCommand` fell back to hardcoded 640x480/false/true when restarted with a null intent (START_STICKY after a crash or soft reboot) and then persisted those values over the real prefs. All 11 such fallbacks now use the stored preference.
