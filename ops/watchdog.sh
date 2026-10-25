#!/usr/bin/env bash
# Laptop/homeserver-side watchdog: revives a dead camera app over ADB with no hands.
#
#   CCTV_AUTH=admin:password ops/watchdog.sh <phone-tailnet-ip-or-host>
#
# Escalation (resets as soon as the phone answers /api/health):
#   3 misses  -> relaunch the app          (adb am start)
#   6 misses  -> force-stop + relaunch     (adb am force-stop; am start)
#   12 misses -> reboot the phone          (adb reboot), at most once per 6 hours
set -u
HOST="${1:?usage: watchdog.sh <phone-ip-or-host>}"
AUTH="${CCTV_AUTH:?set CCTV_AUTH=user:password}"
P=com.zektopic.cctvapp
INTERVAL="${INTERVAL:-30}"
STATE="${TMPDIR:-/tmp}/cctv-watchdog-last-reboot"
miss=0
log() { printf '%s %s\n' "$(date '+%F %T')" "$*"; }
adbc() { adb connect "$HOST:5555" >/dev/null 2>&1; adb -s "$HOST:5555" "$@"; }

while true; do
  if curl -s -m 10 -u "$AUTH" "http://$HOST:8080/api/health" | grep -q '"uptimeMs"'; then
    [ "$miss" -ge 3 ] && log "phone answered again after $miss misses"
    miss=0
  else
    miss=$((miss + 1))
    log "no answer from $HOST:8080 ($miss)"
    if [ "$miss" -eq 3 ]; then
      log "relaunching the app"; adbc shell input keyevent KEYCODE_WAKEUP; adbc shell am start -n $P/.MainActivity
    elif [ "$miss" -eq 6 ]; then
      log "force-stop and relaunch"; adbc shell am force-stop $P; sleep 2; adbc shell am start -n $P/.MainActivity
    elif [ "$miss" -ge 12 ]; then
      last=$(cat "$STATE" 2>/dev/null || echo 0); now=$(date +%s)
      if [ $((now - last)) -gt 21600 ]; then
        log "rebooting the phone"; echo "$now" > "$STATE"; adbc reboot
      else
        log "reboot suppressed (rebooted within the last 6 hours)"
      fi
      miss=6
    fi
  fi
  sleep "$INTERVAL"
done
