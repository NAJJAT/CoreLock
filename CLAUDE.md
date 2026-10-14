# CLAUDE.md

## Devices

The connected phone (RFCN70E6CAY) is my personal device. Tests may run on it,
with these rules:

1. Never run connected*AndroidTest, adb uninstall, pm clear, or anything that
   wipes app data. Install updates only with `adb install -r`.
2. Always use `adb -s RFCN70E6CAY`.
3. Before any step that changes phone settings (airplane mode, Wi-Fi off, VPN
   kill, network switch), tell me what you'll do. Afterwards, put every setting
   back the way it was and remove all test rules.
4. If a test can't be done safely on the phone, skip it and tell me why instead
   of finding a workaround.
5. I commit and push myself; don't commit.

## Files

Always read a file before overwriting it.
