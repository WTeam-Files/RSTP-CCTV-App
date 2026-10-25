#!/usr/bin/env bash
# CCTV phone watchdog (portable: Linux/systemd and macOS/launchd).
#
#   cctv-watchdog.sh [--dry-run] [--once] [--status]
#
# Checks the phone over the tailnet and heals it with the least drastic action that works.
# Healthy means ALL of:
#   * GET /api/health answers, and its newest video frame (snapshot, in JPEG-only mode) is
#     fresher than MAX_AGE_MS;
#   * GET /shot.jpg is HTTP 200 and its X-Frame-Age is under SHOT_MAX_AGE_MS
#     (snapshots are independent of video, so this catches a half-dead pipeline);
#   * optionally (RTSP_PROBE=1, every RTSP_EVERY checks) ffprobe can read the RTSP video.
#
# Escalation, counted in consecutive failed checks:
#   FAIL_RESTART (3)   -> POST /action/restart-service   (fallback: `adb ... force-stop; am start`)
#   +RESTART_REPEAT (4) more failures -> restart again, at most MAX_RESTARTS_PER_HOUR per hour
#   FAIL_REBOOT (12)   -> POST /action/reboot            (fallback: `adb reboot`),
#                         at most MAX_REBOOTS_PER_HOUR per hour (default 1) and never if REBOOT_ENABLED=0
# After an action it waits a grace period (GRACE_RESTART / GRACE_REBOOT seconds) that doubles,
# up to x8, while the outage persists.
# If THIS machine's own tailnet is down it takes no action: that is not the phone's fault.
#
# State (survives watchdog restarts): $STATE/{restarts,reboots}.log (one epoch per action)
#   and $STATE/counters. `touch $STATE/paused` = keep logging, take no action.
# Logging: stdout (journald under systemd); also $LOG when set (used by the macOS LaunchAgent).
set -u
HOST="${HOST:-100.111.127.98}"
PORT="${PORT:-8080}"
RTSP_PORT="${RTSP_PORT:-8554}"
INTERVAL="${INTERVAL:-30}"
MAX_AGE_MS="${MAX_AGE_MS:-20000}"
SHOT_MAX_AGE_MS="${SHOT_MAX_AGE_MS:-30000}"
RTSP_PROBE="${RTSP_PROBE:-0}"
RTSP_EVERY="${RTSP_EVERY:-10}"
FAIL_RESTART="${FAIL_RESTART:-3}"
RESTART_REPEAT="${RESTART_REPEAT:-4}"
FAIL_REBOOT="${FAIL_REBOOT:-12}"
MAX_RESTARTS_PER_HOUR="${MAX_RESTARTS_PER_HOUR:-6}"
MAX_REBOOTS_PER_HOUR="${MAX_REBOOTS_PER_HOUR:-1}"
REBOOT_ENABLED="${REBOOT_ENABLED:-1}"
ADB_FALLBACK="${ADB_FALLBACK:-1}"   # 0 = never try adb (e.g. this machine's adb key is not authorized on the phone)
GRACE_RESTART="${GRACE_RESTART:-120}"
GRACE_REBOOT="${GRACE_REBOOT:-300}"
STATE="${STATE:-${STATE_DIRECTORY:-$HOME/.local/state/cctv-watchdog}}"
LOG="${LOG:-}"
DRY=0; ONCE=0; STATUS=0
for a in "$@"; do case "$a" in --dry-run) DRY=1;; --once) ONCE=1;; --status) STATUS=1;; esac; done
mkdir -p "$STATE" 2>/dev/null
[ -n "$LOG" ] && mkdir -p "$(dirname "$LOG")" 2>/dev/null
BASE="http://$HOST:$PORT"

log() {
  local line; line="$(date '+%F %T') $*"
  echo "$line"
  if [ -n "$LOG" ]; then
    echo "$line" >> "$LOG"
    if [ "$(wc -c < "$LOG" 2>/dev/null || echo 0)" -gt 1048576 ]; then mv -f "$LOG" "$LOG.1"; fi
  fi
}

# Number of lines in a log of epoch seconds that are newer than $2 seconds.
count_since() { local f="$1" win="$2" now; now=$(date +%s); [ -f "$f" ] || { echo 0; return; }
  awk -v n="$now" -v w="$win" '$1 > n - w {c++} END {print c+0}' "$f"; }
prune_log() { local f="$1" now; now=$(date +%s); [ -f "$f" ] && awk -v n="$now" '$1 > n - 86400' "$f" > "$f.tmp" && mv -f "$f.tmp" "$f"; }

if [ "$STATUS" = 1 ]; then
  echo "state dir: $STATE"
  echo "restarts in last hour: $(count_since "$STATE/restarts.log" 3600)   (24h: $(count_since "$STATE/restarts.log" 86400))"
  echo "reboots  in last hour: $(count_since "$STATE/reboots.log" 3600)   (24h: $(count_since "$STATE/reboots.log" 86400))"
  [ -e "$STATE/paused" ] && echo "PAUSED"
  [ -f "$STATE/counters" ] && cat "$STATE/counters"
  exit 0
fi

own_tailnet_up() {
  command -v tailscale >/dev/null 2>&1 || return 0
  local j; j="$(tailscale status --json 2>/dev/null)" || return 0
  [ -n "$j" ] || return 0
  printf '%s' "$j" | python3 -I -c 'import sys,json; sys.exit(0 if json.load(sys.stdin).get("BackendState")=="Running" else 1)' 2>/dev/null
}

# Prints "ok <summary>" or "fail <reason>".
check() {
  local body hdr jpegonly
  body="$(curl -s -m 12 "$BASE/api/health" 2>/dev/null)" || { echo "fail no HTTP answer from $HOST:$PORT"; return; }
  [ -n "$body" ] || { echo "fail empty answer from $HOST:$PORT"; return; }
  local res
  res="$(printf '%s' "$body" | MAX_AGE_MS="$MAX_AGE_MS" python3 -I -c '
import sys, json, os
try:
    d = json.load(sys.stdin); l = d["link"]
except Exception as e:
    print("fail unreadable health JSON (%s)" % e); sys.exit()
limit = int(os.environ["MAX_AGE_MS"])
jo = bool(l.get("jpegOnly"))
age = l["snapshotAgeMs"] if jo else l["encodedFrameAgeMs"]
what = "snapshot" if jo else "video frame"
if age < 0 or age > limit:
    print("fail newest %s is %s ms old (limit %d)" % (what, age, limit))
else:
    print("ok tier %d, %s fps, %s age %d ms, restarts %d, reconnects %d%s" % (l["tier"], l["encoderFps"], what, age, l["serviceRestarts"], l["reconnects"], " [jpeg-only]" if jo else ""))
')"
  [ "${res%% *}" = ok ] || { echo "$res"; return; }
  # Snapshots are a separate pipeline from video: check them on their own.
  hdr="$(curl -s -m 12 -o /dev/null -D - "$BASE/shot.jpg" 2>/dev/null | tr -d '\r')"
  case "$hdr" in HTTP*" 200"*) ;; *) echo "fail /shot.jpg not 200 ($(printf '%s' "$hdr" | head -1))"; return;; esac
  local fa; fa="$(printf '%s' "$hdr" | awk -F': ' 'tolower($1)=="x-frame-age"{print $2}')"
  if [ -n "$fa" ] && [ "$fa" -gt "$SHOT_MAX_AGE_MS" ] 2>/dev/null; then echo "fail /shot.jpg frame is ${fa} ms old (limit $SHOT_MAX_AGE_MS)"; return; fi
  echo "$res, shot age ${fa:-?} ms"
}

rtsp_ok() {
  command -v ffprobe >/dev/null 2>&1 || return 0
  ffprobe -v error -rtsp_transport tcp -timeout 8000000 -select_streams v -show_entries stream=width -of csv=p=0 "rtsp://$HOST:$RTSP_PORT/stream" 2>/dev/null | grep -q '[0-9]'
}

bump() { # bump <restart|reboot>
  local f="$STATE/$1s.log"; date +%s >> "$f"; prune_log "$f"
  { echo "last_$1=$(date '+%F %T')"; echo "restarts_24h=$(count_since "$STATE/restarts.log" 86400)"; echo "reboots_24h=$(count_since "$STATE/reboots.log" 86400)"; } > "$STATE/counters"
}

adb_do() { # adb_do <what>
  [ "$ADB_FALLBACK" = 1 ] || { log "ACTION $1: adb fallback is disabled (ADB_FALLBACK=0)"; return 1; }
  command -v adb >/dev/null 2>&1 || { log "ACTION $1: adb is not installed here"; return 1; }
  adb connect "$HOST:5555" 2>&1 | grep -q connected || { log "ACTION $1: adb connect $HOST:5555 failed"; return 1; }
  if [ "$1" = restart ]; then
    adb -s "$HOST:5555" shell "input keyevent KEYCODE_WAKEUP; am force-stop com.zektopic.cctvapp; am start -n com.zektopic.cctvapp/.MainActivity" >/dev/null 2>&1
  else
    adb -s "$HOST:5555" reboot >/dev/null 2>&1
  fi
}

act() {  # act <restart|reboot>; returns 0 when an action was sent (or would be, in dry-run)
  local what="$1" path limit f
  if [ "$what" = restart ]; then path=/action/restart-service; limit=$MAX_RESTARTS_PER_HOUR; f="$STATE/restarts.log"
  else
    path=/action/reboot; limit=$MAX_REBOOTS_PER_HOUR; f="$STATE/reboots.log"
    [ "$REBOOT_ENABLED" = 1 ] || { log "reboot disabled (REBOOT_ENABLED=0): would have rebooted"; return 1; }
  fi
  if [ -e "$STATE/paused" ]; then log "PAUSED: would $what (POST $path)"; return 1; fi
  if [ "$(count_since "$f" 3600)" -ge "$limit" ]; then
    log "$what suppressed: already $(count_since "$f" 3600) in the last hour (limit $limit/h)"; return 1
  fi
  if [ "$DRY" = 1 ]; then log "DRY-RUN: would POST $BASE$path (fallback: adb $HOST:5555)"; return 0; fi
  local code; code="$(curl -s -m 20 -o /dev/null -w '%{http_code}' -X POST "$BASE$path" 2>/dev/null)"
  if [ "$code" = 200 ]; then log "ACTION $what: POST $path -> 200"; bump "$what"; return 0; fi
  log "ACTION $what: POST $path failed (http ${code:-none}); trying adb"
  if adb_do "$what"; then log "ACTION $what: adb fallback done"; bump "$what"; return 0; fi
  log "ACTION $what: no way to reach the phone (HTTP and adb both failed)"
  return 1
}

fails=0; grace_mult=1; last_ok_log=0; cycle=0
log "watchdog start: host=$HOST:$PORT interval=${INTERVAL}s maxAge=${MAX_AGE_MS}ms restart@$FAIL_RESTART reboot@$FAIL_REBOOT reboots<=${MAX_REBOOTS_PER_HOUR}/h rebootEnabled=$REBOOT_ENABLED rtspProbe=$RTSP_PROBE dry=$DRY state=$STATE"
while true; do
  cycle=$((cycle + 1))
  if ! own_tailnet_up; then
    log "this machine's tailnet is not running; not judging the phone"
  else
    res="$(check)"
    if [ "${res%% *}" = ok ] && [ "$RTSP_PROBE" = 1 ] && [ $((cycle % RTSP_EVERY)) -eq 0 ] && ! rtsp_ok; then res="fail RTSP video not readable"; fi
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
        if act reboot; then sleep_extra=$((GRACE_REBOOT * grace_mult)); fi
        fails=$FAIL_RESTART; grace_mult=$((grace_mult * 2)); [ "$grace_mult" -gt 8 ] && grace_mult=8
      elif [ "$fails" -eq "$FAIL_RESTART" ] || { [ "$fails" -gt "$FAIL_RESTART" ] && [ $(((fails - FAIL_RESTART) % RESTART_REPEAT)) -eq 0 ]; }; then
        if act restart; then sleep_extra=$((GRACE_RESTART * grace_mult)); fi
      fi
      if [ "$DRY" = 0 ] && [ "$sleep_extra" -gt 0 ]; then log "waiting ${sleep_extra}s for the phone to come back"; sleep "$sleep_extra"; fi
    fi
  fi
  [ "$ONCE" = 1 ] && break
  sleep "$INTERVAL"
done
