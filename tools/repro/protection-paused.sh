#!/usr/bin/env bash
# Repro for "Home briefly showed 'Protection is paused' while the tunnel came back
# by itself" (emulator test, 2026-10-24; cause unknown, the log had rotated).
#
# Home shows "paused" whenever PrivacyVpnService.isRunning is false. That flag is
# only true once start-up has finished (encrypted DB opened, lists loaded, tunnel
# established), so anything that restarts the process or the service shows
# "paused" for the length of a start, even when Android or the watchdog brings
# protection back on its own. This script runs the actions from the test, one at a
# time, and records once per sample: the app's PID, whether the service runs,
# whether tun0 exists, and what Home says. Each step is marked in logcat, and the
# log buffer is enlarged first so it does not rotate away again.
#
# Use an emulator or a test phone: it toggles airplane mode, Wi-Fi and Usage
# Access, and (debug builds only) kills the app process. Everything is restored.
#
#   tools/repro/protection-paused.sh [-s SERIAL] [-p PACKAGE] [OUT_DIR]
#
# Before running: protection on, screen unlocked, phone language English (the
# script reads Home and the notification by their English text).
# Read OUT_DIR/summary.txt: every sample that said "paused", with the step it
# happened in, and whether the PID changed (process restart) or tun0 was gone
# (protection really stopped) at that moment.

set -u

PKG=com.privacyguard.app.enterprise
SERIAL=
while getopts "s:p:" opt; do
  case $opt in
    s) SERIAL=$OPTARG ;;
    p) PKG=$OPTARG ;;
    *) echo "usage: $0 [-s SERIAL] [-p PACKAGE] [OUT_DIR]" >&2; exit 2 ;;
  esac
done
shift $((OPTIND - 1))
OUT=${1:-./protection-paused-$(date +%Y%m%d-%H%M%S)}
mkdir -p "$OUT"
SAMPLES="$OUT/samples.csv"
STEP_FILE="$OUT/.step"

adb_() { if [ -n "$SERIAL" ]; then adb -s "$SERIAL" "$@"; else adb "$@"; fi; }
sh_() { adb_ shell "$@" 2>/dev/null | tr -d '\r'; }

# Visible texts with their centre: "x y | text" (same parsing as the emulator tests).
ui() {
  adb_ exec-out uiautomator dump /dev/tty 2>/dev/null | tr '>' '\n' |
    grep -oE '(text|content-desc)="[^"]+"|bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' |
    awk '/^(text|content-desc)=/{sub(/^[^=]*="/,"");sub(/"$/,"");l=$0;next}
         /^bounds=/ && l!=""{gsub(/[^0-9]+/," ");split($0,b," ");printf "%d %d | %s\n",(b[1]+b[3])/2,(b[2]+b[4])/2,l;l=""}'
}
tap_text() {
  local xy; xy=$(ui | grep -E -m1 "\| ($1)\$" | cut -d'|' -f1)
  [ -z "$xy" ] && { echo "  (could not find \"$1\" on screen)"; return 1; }
  sh_ input tap $xy
}
open_home() {
  sh_ am start -n "$PKG/com.privacyguard.app.MainActivity" >/dev/null
  sleep 2
  # The app may resume on a sub-screen (no tab bar) or another tab.
  local i
  for i in 1 2 3; do ui | grep -q "| Home\$" && break; sh_ input keyevent 4; sleep 1; done
  tap_text "Home" >/dev/null
  sleep 1
}

step() {
  echo "$1" | tr ',' ';' > "$STEP_FILE"   # it becomes a CSV field
  sh_ log -t PG_REPRO "step: $1"
  echo "$(date +%H:%M:%S) step: $1"
}
watch_for() { sleep "$1"; }

sampler() {
  echo "time,step,pid,service,tun0,home,notification" > "$SAMPLES"
  while [ -f "$OUT/.running" ]; do
    local t pid svc tun home dump notif
    t=$(date +%H:%M:%S)
    pid=$(sh_ pidof "$PKG" | awk '{print $1}')
    # Running = the service record has a process. A killed service keeps its record
    # (app=null) while Android holds a restart for it.
    svc=$(sh_ dumpsys activity services "$PKG" | sed -n '/PrivacyVpnService/,/^  \*/p' | grep -c "app=ProcessRecord")
    # What the ongoing notification claims (English titles).
    notif=$(sh_ dumpsys notification --noredact | awk -v p="pkg=$PKG " '
        /NotificationRecord\(/ { mine = index($0, p) > 0 }
        mine && match($0, /android\.title=String \((Protection on|Protection off|Protection stopped)/) {
          t = substr($0, RSTART, RLENGTH); sub(/.*\(/, "", t); print t }' | sort -u | paste -sd'+' -)
    # ifconfig: "ip link" is denied to the shell user on some images.
    sh_ ifconfig tun0 | grep -q "inet addr" && tun=up || tun=down
    dump=$(ui)
    if echo "$dump" | grep -q "Protection is paused"; then home=paused
    elif echo "$dump" | grep -q "Protected tunnel running"; then home=protected
    else home=not-on-home; fi
    echo "$t,$(cat "$STEP_FILE" 2>/dev/null),${pid:-none},$([ "${svc:-0}" -gt 0 ] && echo running || echo stopped),$tun,$home,${notif:-none}" >> "$SAMPLES"
  done
}

adb_ get-state >/dev/null 2>&1 || { echo "No device. Start the emulator or pass -s SERIAL." >&2; exit 1; }
sh_ pidof "$PKG" >/dev/null || { echo "$PKG is not running. Turn protection on first." >&2; exit 1; }

echo "Writing to $OUT"
adb_ logcat -G 16M >/dev/null 2>&1 || echo "  (could not enlarge the log buffer)"
adb_ logcat -c
open_home
sleep 3
touch "$OUT/.running"
step "baseline"
sampler &
SAMPLER=$!
watch_for 15

# 1. Protection level chips (the stray tap suspected in the test). A level change
#    reloads the lists in place; it must not restart the tunnel.
step "level Strict"
tap_text "Strict"; watch_for 20
step "level Standard"
tap_text "Standard"; watch_for 20

# 2. Usage Access granted and reset, as done while restoring the test settings.
step "usage access allow"
sh_ appops set "$PKG" GET_USAGE_STATS allow; watch_for 20
step "usage access default"
sh_ appops set "$PKG" GET_USAGE_STATS default; watch_for 20

# 3. Network changes: the tunnel must stay up.
step "wifi off"
sh_ svc wifi disable; watch_for 15
step "wifi on"
sh_ svc wifi enable; watch_for 20
step "airplane on"
sh_ cmd connectivity airplane-mode enable; watch_for 15
step "airplane off"
sh_ cmd connectivity airplane-mode disable; watch_for 25

# 4. Process death (debug builds only: run-as needs a debuggable app). First 60 s
#    untouched, to see whether protection comes back by itself (watchdog, Android's
#    service restart); then Home is opened, which also starts the process.
step "kill process"
PID=$(sh_ pidof "$PKG" | awk '{print $1}')
if [ -n "$PID" ] && sh_ run-as "$PKG" kill -9 "$PID"; then
  watch_for 60
  step "kill process - app opened"
  open_home; watch_for 20
else
  echo "  (skipped: not a debug build, or run-as failed)"
fi

step "end"
watch_for 5
sh_ ifconfig tun0 | grep -q "inet addr" || echo "Protection is off now: turn it on again in the app."
rm -f "$OUT/.running"
wait "$SAMPLER" 2>/dev/null

adb_ logcat -d -v time > "$OUT/logcat.txt"
grep -E "PG_REPRO|PrivacyVpnService|ProtectionWatchdog|WorkerGuard|AndroidRuntime|am_proc_died|am_proc_start|Process $PKG|onRevoke|VpnService" \
  "$OUT/logcat.txt" > "$OUT/logcat-relevant.txt"

{
  echo "Samples that said \"paused\" (time, step, pid, service, tun0):"
  awk -F, 'NR>1 && $6=="paused" {print "  " $1 "  " $2 "  pid=" $3 "  service=" $4 "  tun0=" $5}' "$SAMPLES"
  echo
  echo "PID changes (process restarts):"
  awk -F, 'NR>1 { if (prev != "" && $3 != prev) print "  " $1 "  " $2 "  " prev " -> " $3; prev=$3 }' "$SAMPLES"
  echo
  echo "Samples with tun0 down:"
  awk -F, 'NR>1 && $5=="down" {print "  " $1 "  " $2}' "$SAMPLES"
  echo
  echo "Notification says \"Protection on\" while tun0 is down (a false claim):"
  awk -F, 'NR>1 && $5=="down" && $7 ~ /Protection on/ {print "  " $1 "  " $2 "  " $7}' "$SAMPLES"
  echo
  echo "Read with logcat-relevant.txt: a \"paused\" sample with the same PID and tun0 up"
  echo "means Home was wrong; with a new PID, the process restarted; with tun0 down,"
  echo "protection really stopped."
} > "$OUT/summary.txt"
rm -f "$STEP_FILE"
cat "$OUT/summary.txt"
