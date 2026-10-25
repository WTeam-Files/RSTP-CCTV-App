#!/usr/bin/env bash
# CCTV phone watchdog. Runs forever (normally as a macOS LaunchAgent, see install-launchagent.sh),
# checks the phone over the tailnet and heals it with the least drastic action that works.
#
#   cctv-watchdog.sh [--dry-run] [--once]
#
# "Healthy" = /api/health answers and the newest video frame (or snapshot, in JPEG-only mode)
# is fresher than MAX_AGE_MS. Escalation, counted in consecutive failed checks:
#   FAIL_RESTART (3)  -> POST /action/restart-service        (falls back to `adb ... am start`)
#   then every RESTART_REPEAT (4) more failures -> restart again
#   FAIL_REBOOT (12)  -> POST /action/reboot                 (falls back to `adb reboot`),
#                        at most once per REBOOT_MIN_GAP seconds (6 h)
# After any action it waits a grace period before judging again, and the grace grows if the
# problem persists. If THIS Mac's tailnet is down it does nothing (that is not the phone's fault).
#
# Controls:  touch "$STATE/paused"   -> keeps logging, takes no action      (rm to resume)
#            --dry-run               -> logs what it WOULD do, does nothing
# Everything is overridable with environment variables (HOST, PORT, INTERVAL, ...).
set -u
HOST="${HOST:-100.111.127.98}"
PORT="${PORT:-8080}"
INTERVAL="${INTERVAL:-30}"
MAX_AGE_MS="${MAX_AGE_MS:-20000}"
FAIL_RESTART="${FAIL_RESTART:-3}"
RESTART_REPEAT="${RESTART_REPEAT:-4}"
FAIL_REBOOT="${FAIL_REBOOT:-12}"
REBOOT_MIN_GAP="${REBOOT_MIN_GAP:-21600}"
GRACE_RESTART="${GRACE_RESTART:-120}"
GRACE_REBOOT="${GRACE_REBOOT:-300}"
STATE="${STATE:-$HOME/Library/Application Support/cctv-watchdog}"
LOG="${LOG:-$HOME/Library/Logs/cctv-watchdog.log}"
DRY=0; ONCE=0
for a in "$@"; do case "$a" in --dry-run) DRY=1;; --once) ONCE=1;; esac; done
mkdir -p "$STATE" "$(dirname "$LOG")"
BASE="http://$HOST:$PORT"

log() {
  local line; line="$(date '+%F %T') $*"
  echo "$line" | tee -a "$LOG" >/dev/null
  [ -t 1 ] && echo "$line"
  if [ "$(stat -f %z "$LOG" 2>/dev/null || echo 0)" -gt 1048576 ]; then mv -f "$LOG" "$LOG.1"; fi
}

mac_tailnet_up() {
  tailscale status --json 2>/dev/null | python3 -I -c 'import sys,json; sys.exit(0 if json.load(sys.stdin).get("BackendState")=="Running" else 1)' 2>/dev/null
}

# Prints "ok <summary>" or "fail <reason>".
check() {
  local body
  body="$(curl -s -m 12 "$BASE/api/health" 2>/dev/null)" || { echo "fail no HTTP answer from $HOST:$PORT"; return; }
  [ -n "$body" ] || { echo "fail empty answer from $HOST:$PORT"; return; }
  printf '%s' "$body" | MAX_AGE_MS="$MAX_AGE_MS" python3 -I -c '
import sys, json, os
try:
    d = json.load(sys.stdin); l = d["link"]
except Exception as e:
    print("fail unreadable health JSON (%s)" % e); sys.exit()
limit = int(os.environ["MAX_AGE_MS"])
age = l["snapshotAgeMs"] if l.get("jpegOnly") else l["encodedFrameAgeMs"]
what = "snapshot" if l.get("jpegOnly") else "video frame"
if age < 0 or age > limit:
    print("fail newest %s is %s ms old (limit %d)" % (what, age, limit))
else:
    print("ok tier %d, %s fps, %s age %d ms, restarts %d, reconnects %d" % (l["tier"], l["encoderFps"], what, age, l["serviceRestarts"], l["reconnects"]))
'
}

act() {  # act <restart|reboot>
  local what="$1" path
  [ "$what" = restart ] && path=/action/restart-service || path=/action/reboot
  if [ -e "$STATE/paused" ]; then log "PAUSED: would $what (POST $path)"; return 1; fi
  if [ "$DRY" = 1 ]; then log "DRY-RUN: would POST $BASE$path (fallback: adb $HOST:5555)"; return 0; fi
  local code; code="$(curl -s -m 20 -o /dev/null -w '%{http_code}' -X POST "$BASE$path" 2>/dev/null)"
  if [ "$code" = 200 ]; then log "ACTION $what: POST $path -> 200"; return 0; fi
  log "ACTION $what: POST $path failed (http ${code:-none}); trying adb"
  if adb connect "$HOST:5555" 2>&1 | grep -q connected; then
    if [ "$what" = restart ]; then
      adb -s "$HOST:5555" shell "input keyevent KEYCODE_WAKEUP; am force-stop com.zektopic.cctvapp; am start -n com.zektopic.cctvapp/.MainActivity" >/dev/null 2>&1 && { log "ACTION restart: adb relaunch done"; return 0; }
    else
      adb -s "$HOST:5555" reboot >/dev/null 2>&1 && { log "ACTION reboot: adb reboot sent"; return 0; }
    fi
  fi
  log "ACTION $what: no way to reach the phone (HTTP and adb both failed)"
  return 1
}

fails=0; grace_mult=1; last_ok_log=0
log "watchdog start: host=$HOST:$PORT interval=${INTERVAL}s maxAge=${MAX_AGE_MS}ms restart@$FAIL_RESTART reboot@$FAIL_REBOOT dry=$DRY"
while true; do
  if ! mac_tailnet_up; then
    log "this Mac's tailnet is not running; not judging the phone"
  else
    res="$(check)"
    if [ "${res%% *}" = ok ]; then
      if [ "$fails" -gt 0 ]; then log "RECOVERED after $fails failed checks: ${res#ok }"; fi
      fails=0; grace_mult=1
      now=$(date +%s)
      if [ $((now - last_ok_log)) -ge 600 ]; then log "ok: ${res#ok }"; last_ok_log=$now; fi
    else
      fails=$((fails + 1))
      log "check failed ($fails): ${res#fail }"
      sleep_extra=0
      if [ "$fails" -ge "$FAIL_REBOOT" ]; then
        last=$(cat "$STATE/last-reboot" 2>/dev/null || echo 0); now=$(date +%s)
        if [ $((now - last)) -ge "$REBOOT_MIN_GAP" ]; then
          if act reboot; then [ "$DRY" = 1 ] || echo "$now" > "$STATE/last-reboot"; sleep_extra=$((GRACE_REBOOT * grace_mult)); fi
        else
          log "reboot suppressed: last one was $(( (now - last) / 60 )) min ago (minimum $((REBOOT_MIN_GAP / 60)) min)"
        fi
        fails=$((FAIL_RESTART)); grace_mult=$((grace_mult * 2)); [ "$grace_mult" -gt 8 ] && grace_mult=8
      elif [ "$fails" -eq "$FAIL_RESTART" ] || { [ "$fails" -gt "$FAIL_RESTART" ] && [ $(((fails - FAIL_RESTART) % RESTART_REPEAT)) -eq 0 ]; }; then
        if act restart; then sleep_extra=$((GRACE_RESTART * grace_mult)); fi
      fi
      if [ "$DRY" = 0 ] && [ "$sleep_extra" -gt 0 ]; then log "waiting ${sleep_extra}s for the phone to come back"; sleep "$sleep_extra"; fi
    fi
  fi
  [ "$ONCE" = 1 ] && break
  sleep "$INTERVAL"
done
