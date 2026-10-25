
## Appliance changes (WTeam-Files fork)
- Defaults for a fresh install: 1280x720 (`AppPreferences.DEFAULT_WIDTH/HEIGHT`, service fields follow), `video_fps` 30 and audio OFF (already the defaults), snapshot active+idle interval 5000 ms (`CaptureProfile`), start-on-boot and auto-start-on-launch now default ON. Stored prefs still win and everything stays editable from the dashboard/app.
- `SnapshotBuffer.kt`: 3 h rolling JPEG buffer at `getExternalFilesDir/snapshot-buffer/YYYY-MM-DD/HH/MMSS.jpg`. Atomic tmp+rename, written on a single-thread executor, pruned by age (3 h) and count cap (4000) at most once a minute, skipped below 200 MB free, all failures swallowed. Capture loop now always runs while streaming so the buffer fills without viewers. Tests: `SnapshotBufferTest`.
- `BootReceiver`: default-on; after `startForegroundService` it also launches `MainActivity` when SYSTEM_ALERT_WINDOW is granted, because Android 11 denies camera access to a background FGS unless the app is visible.
- `CaptureProfileTest` updated for the new interval defaults.
- `ci.yml`: ubuntu-latest + `cctv-debug-apk` artifact.
- Fix: `CctvServerService.onStartCommand` fell back to hardcoded 640x480/false/true when restarted with a null intent (START_STICKY after a crash or soft reboot) and then persisted those values over the real prefs. All 11 such fallbacks now use the stored preference.
- Direct boot / retry: prefs moved to device-protected storage (one-time migration from credential-encrypted, runs after unlock); `EventStore` is now lazy and the captioner guarded, so a locked boot no longer crashes `onCreate`; `SnapshotBuffer` is created lazily once the user is unlocked; service + `BootReceiver` are `directBootAware` (LOCKED_BOOT_COMPLETED + BOOT_COMPLETED); `StartRetryPolicy` (2/5/10/20/30/60 s backoff, fresh process after 5 consecutive failures) driven by `onConnectionFailed`, a 45 s no-frame watchdog in the snapshot loop, and ACTION_USER_UNLOCKED. Tests: `StartRetryPolicyTest`. Trade-off: stream credentials now sit in device-protected storage.

## Live video in the browser
- Dashboard live view used to poll /shot.jpg (JPEG from the GL surface: ~2 distinct frames/s at best on this phone, 0.2 fps at the 5 s snapshot interval). RTSP itself delivers ~29 fps.
- New: `CctvRtspCamera2` (twin of the library's final `RtspServerCamera2`) copies each encoded H.264 frame into `LiveVideoHub`; `Fmp4Muxer` wraps frames as fragmented MP4 (`SpsParser` reads the size); `WebServer` serves it chunked at `GET /live.mp4` behind the usual origin/auth checks; the Live tab plays it in a `<video>`, falls back to snapshots if video can't play and retries after 20 s. Idle cost: with no viewer connected the hub does nothing.
- Tests: `Fmp4MuxerTest` (box structure, trun data offset, Annex B conversion, SPS size incl. cropping).
- Flashlight turned off on the device (was on). video_fps 40 is accepted but the camera delivers ~29-30 fps; left at 30.
