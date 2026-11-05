# Phone test for 1.2.0: networks, vendors and battery

Checks that only a real phone on a real network can answer, added for 1.2 (hardening plan,
task 0.4). They come **on top of** `docs/PHONE_TEST_1.1.0.md`: run that checklist first (with
the 1.2.0 APK in place of 1.1.0), then these.

**Who:** the owner, or a tester with the phones below. **Build:** the signed release APK
that will be published, nothing else. Package: `com.privacyguard.app.enterprise`.

## How to use this checklist

- Same rules as the 1.1.0 test: steps in order, every result in the table at the end
  (PASS, FAIL, or SKIP with a reason), a **blocker** FAIL stops the release.
- Commands run on the computer with the phone on USB. Always pass `-s PHONE_ID` (the
  serial from `adb devices`), so no command reaches another device or an emulator.
- Some checks need a particular phone or network. If you don't have it, write SKIP and
  what was missing; do not guess a result.

| Check | Needs |
|---|---|
| A | Mobile data on an IPv6-only network with 464XLAT (common on T-Mobile US, many EU and Asian carriers) |
| B | Any phone, Android 9 or newer (Private DNS) |
| C | A Samsung phone and a Xiaomi (or Redmi/POCO) phone |
| D | Any phone, two ordinary days |
| E | A Wi-Fi network with a login page (hotel, café, train, airport) |
| F | Any phone |

---

### A. IPv6-only mobile data with 464XLAT

On these networks the phone has only an IPv6 address from the carrier; Android adds a small
translator (CLAT) so apps that need IPv4 still work. CoreLock must not break that.

1. Wi-Fi off, mobile data on. Check that the network is IPv6-only with CLAT:
   ```
   adb -s PHONE_ID shell ip -o addr | grep -E "clat|v4-"
   adb -s PHONE_ID shell ip -o -4 addr | grep -v " lo "
   ```
   **Expected:** the first command shows an interface named `v4-…` or `clat…`; the second
   shows no IPv4 address on the mobile interface (`rmnet…`), only on `v4-…` and `tun0`.
   If there is no `v4-`/`clat` interface, this network is not 464XLAT: write SKIP.
2. Protection on at **Standard**. In Chrome open five sites, including one known to be
   IPv4-only (most smaller news or shop sites; check on the computer with
   `nslookup -type=AAAA <site>`: no AAAA answer means IPv4-only).
   **Expected:** every site loads; ads from separate ad servers are gone.
3. A WhatsApp (or Signal) **voice and video call**, 2 minutes each.
   **Expected:** both stay up, with audio and video both ways.
4. An app that talks to an IPv4 literal address, if you have one (some games, some
   banking apps). **Expected:** works as without CoreLock.
5. Settings › Advanced › Diagnostics. **Expected:** the IPv6 line counts only blocked
   packets (an increase during the test is normal); no error states.

**Fail (blocker):** a site, call or app works without CoreLock but not with it on this
network.

### B. Private DNS (Android 9+)

Private DNS set to a hostname makes Android send DNS past CoreLock, so DNS blocking
(layer 1) is off; server-name blocking (layer 3) still works.

1. Android Settings › Network › Private DNS › **Private DNS provider hostname**,
   enter `dns.google`, save. Open CoreLock.
   **Expected:** Home shows the Private DNS warning, with a way to open the setting.
2. Stop and start protection. **Expected:** the warning appears before protection
   starts, not only afterwards.
3. With protection on, open a site with ads in Chrome. **Expected:** pages load. Some ads
   are still stopped (by server name); note how many fewer than with Private DNS off.
4. Set Private DNS to **Automatic**. **Expected:** the warning is gone within a few seconds
   (no restart), pages load normally, ads are blocked as usual.
5. Set Private DNS to **Off**. **Expected:** the same as Automatic.

**Fail:** no warning in step 1 or 2, a warning that stays in step 4, or pages that stop
loading in any mode.

### C. Screen off on Samsung and Xiaomi

Some vendors stop background apps more aggressively than Android itself. Run each step on
**both** phones, with the vendor's default battery setting for CoreLock (do not exempt it).

1. Protection on at Standard. Note the time. Screen off, phone untouched, **60 minutes**
   (on the charger is fine; do it off the charger once if you can).
2. Unlock. **Expected:** the CoreLock notification is still there, Home says protected,
   and a site loads at once.
3. Check that the service really ran the whole time:
   ```
   adb -s PHONE_ID shell dumpsys activity services com.privacyguard.app.enterprise | grep -E "PrivacyVpnService|createTime|lastActivity"
   adb -s PHONE_ID shell pidof com.privacyguard.app.enterprise
   ```
   **Expected:** the service is listed, with a `createTime` before step 1.
4. Repeat steps 1–3 with the screen off for a **night** (6 hours or more).
5. Samsung: Settings › Battery › Background usage limits: note whether CoreLock is in
   "Sleeping apps" or "Deep sleeping apps". Xiaomi: Settings › Apps › CoreLock › Battery
   saver: note the mode. **Expected:** not put to (deep) sleep by default; if it is, note
   it and check that Settings › Diagnostics › Battery shows the problem.
6. If protection stopped in any step: **Expected:** the notification and Home say so
   (never "protected" while it isn't), and turning on Always-on VPN with "Block
   connections without VPN" blocks traffic until it comes back.

**Fail (blocker):** Home or the notification says protected while traffic goes past
CoreLock. **Fail (not a blocker):** the vendor stops the service; record it for the
docs, with the vendor's setting that prevents it.

### D. 24-hour battery comparison

One day without CoreLock and one day with it, used the same way, so CoreLock's real cost
can be told apart from Android's habit of charging a VPN for other apps' traffic.

1. **Day 1 (baseline):** protection off, and CoreLock not set as Always-on VPN. Charge to
   100 %, unplug, then reset the counters:
   ```
   adb -s PHONE_ID shell dumpsys batterystats --reset
   ```
   Use the phone normally for 24 hours (or until 20 % left). Then save:
   ```
   adb -s PHONE_ID shell dumpsys batterystats --charged > day1.txt
   ```
   Write down: battery % used, hours, screen-on time (Settings › Battery).
2. **Day 2 (CoreLock at Standard):** the same as day 1 with protection on, on a similar
   day (the same networks, similar screen-on time). Save `day2.txt` the same way.
3. Compare, per hour of screen-on time and per hour overall:
   ```
   grep -E "Estimated power use|Screen on|Uid u0a" day1.txt | head -20
   grep -E "Estimated power use|Screen on|Uid u0a" day2.txt | head -20
   ```
   and CoreLock's own line in day 2 (`adb -s PHONE_ID shell dumpsys package com.privacyguard.app.enterprise | grep userId`
   gives its uid, `u0a…`).
4. Write both days in the table: % used, hours, screen-on hours, % per hour.

**Fail:** day 2 drains more than **5 percentage points** more per 24 hours than day 1 at
similar use, or CoreLock's own share is above 10 % of the day (the 1.1.0 rule). A
difference this small is noisy: if it fails by a little, repeat both days before
deciding.

### E. Captive portal (login page Wi-Fi)

1. Protection on at **Strict**, with Always-on VPN and "Block connections without VPN"
   **on** (the hardest case). Connect to the Wi-Fi.
2. **Expected:** Android shows "Sign in to network"; tapping it opens the login page;
   the login works.
3. After logging in: **Expected:** sites load through CoreLock within about 10 seconds;
   ads are blocked.
4. Repeat with Always-on VPN off. **Expected:** the same.

**Fail (blocker):** the login page does not open or does not work while CoreLock is on,
and works with it off. Write which network (hotel name, train operator) so it can be
checked again.

### F. Secure connections that can't be read (new in 1.2)

At Standard and Strict, CoreLock now resets a secure connection whose start it cannot read
(it used to let it through unchecked). Ordinary browsers and apps always send a readable
start, so nothing should break.

1. Protection on at **Standard**. For 30 minutes use Chrome (ten sites, a login, a video),
   YouTube, WhatsApp, a banking app and two apps you use daily.
   **Expected:** everything works as at 1.1.0.
2. Settings › Advanced › Diagnostics › *Server-name blocking (SNI)*: note the second number
   ("Reset because their TLS start could not be read").
   **Expected:** 0, or a handful at most. If it is more than 10 after 30 minutes, note which
   app was in use: that app is sending something unusual and needs a look before release.
3. Repeat step 1 for 10 minutes at **Strict**. **Expected:** the same.
4. Switch to **Minimal**. **Expected:** the number no longer grows.

**Fail (blocker):** an app or site works at 1.1.0 and stops working at Standard in 1.2.0.

---

## Results

Phones (model, Android version, vendor UI version): ________
APK SHA-256 (first 8): ________ Dates: ________

| # | Check | Result (PASS / FAIL / SKIP) | Numbers and notes |
|---|---|---|---|
| A | IPv6-only + 464XLAT: sites (one IPv4-only), voice and video call, IPv4-literal app | | Carrier: ___ CLAT interface: ___ |
| B | Private DNS: warning on Home and before start, goes away in Automatic, pages load in all modes | | Ads seen with hostname mode: ___ |
| C | Screen off 60 min and one night, Samsung and Xiaomi, true status if stopped | | Samsung: ___ Xiaomi: ___ |
| D | Battery, day without vs day with CoreLock | | Day 1: ___ % / ___ h (screen ___ h) Day 2: ___ % / ___ h (screen ___ h) |
| E | Captive portal at Strict with lockdown, and without | | Network: ___ |
| F | Unreadable-TLS resets at Standard and Strict, nothing broken | | Count after 30 min: ___ |
