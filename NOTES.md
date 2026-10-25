
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

# Operator playbook (remote camera phone)

Phone = Galaxy J4 Core, Tailscale node `galaxy-j4-core`, tailnet address **100.111.127.98**
(Mac = 100.105.203.121, homeserver = 100.81.103.112). Use the tailnet address, not the Wi-Fi
address (192.168.4.30): the Wi-Fi address is only reachable from the phone's own LAN.
The dashboard and RTSP have **no password** (owner decision); they are protected only by being on
the tailnet / LAN. `ops/` holds the scripts mentioned below.

## Look at it
- Dashboard: `http://100.111.127.98:8080` (Live tab plays real 1280x720 video; Frames = 3 h buffer; Health = link + recovery)
- RTSP: `ffplay -rtsp_transport tcp rtsp://100.111.127.98:8554/stream`
- One still: `curl -s -o shot.jpg http://100.111.127.98:8080/shot.jpg`
- Health as JSON: `curl -s http://100.111.127.98:8080/api/health` (look at `.link`: tier, encoderFps, rssiDbm, linkMbps, reconnects, serviceRestarts, lastError)
- Phone screen: `adb connect 100.111.127.98:5555 && adb -s 100.111.127.98:5555 exec-out screencap -p > screen.png`

## Read logs
- App event log (survives restarts): `curl -s 'http://100.111.127.98:8080/api/logs?lines=200'`
- Android log: `adb -s 100.111.127.98:5555 logcat -d | tail -200`

## Fix things, least to most drastic
1. Weak link? Force a lower quality: `curl -X POST 'http://100.111.127.98:8080/action/set-setting?key=link_tier&value=3'` (0 best .. 4 lowest, `auto` to release). Video off, snapshots only: `...key=jpeg_only&value=true` (`false` to return).
2. Restart the app (about 15 s): `curl -X POST http://100.111.127.98:8080/action/restart-service`
3. Reboot the phone (about 1-2 min): `curl -X POST http://100.111.127.98:8080/action/reboot` (needs the app to hold root in Magisk, granted once) or `adb -s 100.111.127.98:5555 reboot`
4. Dead app with no HTTP answer: `adb connect 100.111.127.98:5555 && adb -s 100.111.127.98:5555 shell am start -n com.zektopic.cctvapp/.MainActivity`
5. Unattended revival from the homeserver/Mac: `ops/watchdog.sh 100.111.127.98` (relaunch after 3 misses, force-stop after 6, reboot after 12, at most one reboot per 6 h).

## If the tailnet is down
- The phone reconnects by itself (Tailscale is always-on and exempt from battery optimisation). If the node shows offline in `tailscale status`, the phone is off, off Wi-Fi, or Tailscale was signed out.
- Reach it on its own LAN instead: `http://192.168.4.30:8080`, `adb connect 192.168.4.30:5555` (the LAN address can change; check the router).
- Last resort is physical: plug in USB, `adb devices`, then `ops/phone-setup.sh <serial>`.
- Key expiry: in the Tailscale admin console choose "Disable key expiry" for `galaxy-j4-core`, otherwise it will need an on-phone sign-in again.

## After any reinstall (CI builds are signed with a fresh debug key each time)
1. Download the `cctv-debug-apk` artifact from the latest successful run on `WTeam-Files/RSTP-CCTV-App` and verify it before touching the phone.
2. `adb uninstall com.zektopic.cctvapp && adb install app-debug.apk`
3. `ops/phone-setup.sh <serial> --tailscale`  (grants, doze whitelist, Home = this app, keep-awake, ADB-TCP, updater off)
4. **Launch the app once** (`am start -n com.zektopic.cctvapp/.MainActivity`): a never-launched app does not receive BOOT_COMPLETED.
5. The first `/action/reboot` makes Magisk ask whether the app may use root; accept it once on the phone (a reinstall resets this).

## What starts by itself
Boot -> Android starts the CCTV app as Home and the boot receiver starts the camera service -> stream, dashboard (8080), RTSP (8554), ADB-TCP (5555) and Tailscale all come up with no input. The phone has no lock PIN and the keyguard is disabled.

## Status check, reaching the phone, and the watchdog

**Quick status (from any machine on the tailnet):**
```
curl -s http://100.111.127.98:8080/api/health | python3 -m json.tool      # .link = tier, encoderFps, frame ages, restarts
tailscale status | grep galaxy-j4-core                                     # is the node online?
nc -z 100.111.127.98 8080 5555 8554                                        # dashboard / adb / rtsp ports
```
**From any other machine on the tailnet:** nothing to install on the phone. Install Tailscale on that machine, sign in to the same account, then use the same `100.111.127.98` address for the dashboard (8080), RTSP (8554) and `adb connect 100.111.127.98:5555`.
Auth is intentionally OFF (dashboard, RTSP, `/action/*`): anyone on the tailnet or on the phone's Wi-Fi can view and control it.

**Laptop watchdog (macOS LaunchAgent `dev.cctv.watchdog`)** — `ops/cctv-watchdog.sh`, installed by `ops/install-launchagent.sh`.
- Every 30 s it reads `/api/health`; healthy = it answers and the newest video frame (snapshot, in JPEG-only mode) is under 20 s old.
- 3 failed checks in a row -> `POST /action/restart-service` (falls back to `adb ... am start`); repeats every 4 more failures.
- 12 failed checks -> `POST /action/reboot` (falls back to `adb reboot`), at most once per 6 hours. After each action it waits 2 min (5 min after a reboot), and the wait doubles while the problem persists (max x8).
- It does nothing if this Mac's own tailnet is down (that is not the phone's fault).
- Log: `~/Library/Logs/cctv-watchdog.log`. Installed files: `~/Library/Application Support/cctv-watchdog/` and `~/Library/LaunchAgents/dev.cctv.watchdog.plist` (outside the repo).
- `ops/install-launchagent.sh install [HOST]` / `status` / `pause` (keeps logging, takes no action) / `resume` / `uninstall`.
- Dry run (logs what it would do, sends nothing): `HOST=127.0.0.1 PORT=9 INTERVAL=1 FAIL_RESTART=2 FAIL_REBOOT=5 LOG=/tmp/wd.log bash ops/cctv-watchdog.sh --dry-run`.
- To disable: `ops/install-launchagent.sh pause` (temporary) or `uninstall`. Disable it before deliberately taking the phone down, or it will "heal" it.

**Known boot window.** After a reboot the phone is reachable over the tailnet at about 95 s (dashboard) and 112 s (video), but ADB over TCP (5555) can stay closed for about 25 s after the phone's own boot finishes: this ROM keeps `persist.adb.tcp.port=5555` but never opens the port from it, so the app (which holds root) re-opens it ~25 s after boot (and re-checks every ~30 s). A remote `adb connect` in that window is refused; just retry. USB adb is unaffected.
