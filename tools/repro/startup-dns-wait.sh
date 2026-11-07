# How long the internet waits while CoreLock starts (docs/PHONE_TEST_1.2.0.md, check G).
#
# Runs on the phone, not the computer:
#   adb -s PHONE_ID push tools/repro/startup-dns-wait.sh /data/local/tmp/
#   adb -s PHONE_ID shell sh /data/local/tmp/startup-dns-wait.sh
# Start it, then turn protection on within 10 s.
#
# Until CoreLock has loaded its lists it lets nothing through (fail closed). Lookups made
# meanwhile wait in the tunnel and are answered once filtering starts, so the wait shows
# as slow lookups, not failed ones. The script looks up a new name every half second (a
# new name each time, so Android's DNS cache can't answer it) and prints every lookup that
# took 3 s or more or failed. RESULT is the time from the start of the first slow lookup
# to the end of the last one.
#
# The names are <unique>.203.0.113.7.nip.io: nip.io answers them with the documentation
# address 203.0.113.7, so nothing else is contacted. ping's exit code 1 (no ICMP reply)
# still means the name resolved; 2 means it did not.
n=0; end=$(( $(date +%s) + 120 )); first=""; last=""; fast=0
while [ "$(date +%s)" -lt "$end" ]; do
  n=$((n+1)); t0=$(date +%s)
  ping -c1 -W1 "t${t0}n$n.203.0.113.7.nip.io" >/dev/null 2>&1; rc=$?
  t1=$(date +%s); d=$((t1 - t0))
  if [ $d -ge 3 ] || [ $rc -eq 2 ]; then
    echo "$(date +%T) lookup $n took ${d} s ($( [ $rc -eq 2 ] && echo failed || echo resolved))"
    [ -z "$first" ] && first=$t0; last=$t1; fast=0
  else
    fast=$((fast+1))
    [ -n "$first" ] && [ $fast -ge 5 ] && break
  fi
  sleep 0.5
done
if [ -z "$first" ]; then echo "RESULT: no slow lookup (did protection start while the script ran?)"
else echo "RESULT: lookups waited $((last - first)) s"; fi
