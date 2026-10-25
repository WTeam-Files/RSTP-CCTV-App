#!/usr/bin/env bash
# One-shot, idempotent configuration of the camera phone, run from a machine with adb access
# (USB, or `adb connect <ip>:5555`). Safe to re-run after every reinstall: a reinstall of a
# debug build wipes the app's runtime grants, so this re-applies them.
#
#   ops/phone-setup.sh [adb-serial] [--tailscale] [--clear-pin OLDPIN]
#
# Does NOT touch the lock PIN unless --clear-pin is given, and does not set Tailscale always-on
# unless --tailscale is given (do that after the phone has signed in to the tailnet).
set -u
SERIAL="${1:-6068cecb}"
case "$SERIAL" in --*) SERIAL=6068cecb ;; esac
CLEAR_PIN=""; WITH_TS=0
while [ $# -gt 0 ]; do
  [ "$1" = "--clear-pin" ] && CLEAR_PIN="${2:-}"
  [ "$1" = "--tailscale" ] && WITH_TS=1
  shift
done

A="adb -s $SERIAL"
P=com.zektopic.cctvapp
TS=com.tailscale.ipn
say() { printf '== %s\n' "$*"; }

say "app permissions"
for perm in CAMERA RECORD_AUDIO; do $A shell pm grant $P android.permission.$perm; done
$A shell pm grant $P android.permission.WRITE_SECURE_SETTINGS
$A shell appops set $P SYSTEM_ALERT_WINDOW allow
$A shell appops set $P RUN_ANY_IN_BACKGROUND allow

say "doze / battery whitelist (app + Tailscale)"
$A shell cmd deviceidle whitelist +$P
$A shell cmd deviceidle whitelist +$TS 2>/dev/null
$A shell appops set $TS RUN_ANY_IN_BACKGROUND allow 2>/dev/null

say "boot into the CCTV app (Home = this app)"
$A shell cmd package set-home-activity $P/.MainActivity

say "keep awake while charging, no screensaver, dim screen"
$A shell svc power stayon true
$A shell settings put secure screensaver_enabled 0
$A shell settings put secure screensaver_activate_on_dock 0
$A shell settings put secure screensaver_activate_on_sleep 0
$A shell settings put system screen_brightness_mode 0
$A shell settings put system screen_brightness 10

say "no surprise reboots: disable the LineageOS updater"
$A shell pm disable-user --user 0 org.lineageos.updater 2>&1 | tail -1

say "ADB over TCP :5555, persistent across reboots (needs root for the property)"
$A shell "su -c 'setprop persist.adb.tcp.port 5555'" 2>&1 | tail -1
$A tcpip 5555 2>&1 | tail -1
$A wait-for-device

if [ "$WITH_TS" = 1 ]; then
  say "Tailscale: always-on VPN"
  $A shell settings put secure always_on_vpn_app $TS
  $A shell settings put secure always_on_vpn_lockdown 0
fi

if [ -n "$CLEAR_PIN" ]; then
  say "clearing the lock PIN and disabling the keyguard (explicitly requested)"
  $A shell locksettings clear --old "$CLEAR_PIN"
  $A shell locksettings set-disabled true
fi

say "launch"
$A shell input keyevent KEYCODE_WAKEUP
$A shell am start -n $P/.MainActivity
say "done"
