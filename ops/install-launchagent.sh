#!/usr/bin/env bash
# Installs / removes the CCTV watchdog as a per-user macOS LaunchAgent. Idempotent.
#   ops/install-launchagent.sh install [HOST]   (default HOST 100.111.127.98, the phone's tailnet address)
#   ops/install-launchagent.sh status
#   ops/install-launchagent.sh pause | resume     (keep running, take no action)
#   ops/install-launchagent.sh uninstall
set -eu
LABEL="dev.cctv.watchdog"
HERE="$(cd "$(dirname "$0")" && pwd)"
DEST="$HOME/Library/Application Support/cctv-watchdog"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
LOG="$HOME/Library/Logs/cctv-watchdog.log"
UIDN="$(id -u)"
cmd="${1:-status}"

case "$cmd" in
  install)
    HOST="${2:-100.111.127.98}"
    mkdir -p "$DEST" "$HOME/Library/LaunchAgents" "$HOME/Library/Logs"
    cp "$HERE/cctv-watchdog.sh" "$DEST/cctv-watchdog.sh"; chmod +x "$DEST/cctv-watchdog.sh"
    cat > "$PLIST" <<PL
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key><array><string>/bin/bash</string><string>$DEST/cctv-watchdog.sh</string></array>
  <key>EnvironmentVariables</key><dict>
    <key>HOST</key><string>$HOST</string>
    <key>PATH</key><string>/opt/homebrew/bin:/usr/local/bin:/Users/$USER/Library/Android/sdk/platform-tools:/usr/bin:/bin:/usr/sbin:/sbin</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>30</integer>
  <key>StandardOutPath</key><string>$HOME/Library/Logs/cctv-watchdog.out</string>
  <key>StandardErrorPath</key><string>$HOME/Library/Logs/cctv-watchdog.out</string>
</dict></plist>
PL
    launchctl bootout "gui/$UIDN/$LABEL" 2>/dev/null || true
    launchctl bootstrap "gui/$UIDN" "$PLIST"
    launchctl kickstart -k "gui/$UIDN/$LABEL" 2>/dev/null || true
    echo "installed $LABEL -> $PLIST (script: $DEST/cctv-watchdog.sh, log: $LOG)"
    ;;
  uninstall)
    launchctl bootout "gui/$UIDN/$LABEL" 2>/dev/null || true
    rm -f "$PLIST"
    rm -rf "$DEST"
    echo "removed $LABEL (log kept at $LOG)"
    ;;
  pause)  mkdir -p "$DEST"; touch "$DEST/paused"; echo "paused: still logging, no actions";;
  resume) rm -f "$DEST/paused"; echo "resumed";;
  status)
    launchctl print "gui/$UIDN/$LABEL" 2>/dev/null | grep -E "state =|pid =|last exit" || echo "not loaded"
    [ -e "$DEST/paused" ] && echo "PAUSED"
    tail -5 "$LOG" 2>/dev/null || true
    ;;
  *) echo "usage: $0 install [HOST] | status | pause | resume | uninstall"; exit 2;;
esac
