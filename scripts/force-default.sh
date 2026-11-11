#!/usr/bin/env bash
# Switches off the stock Google TV home screen so the Home button can only open this launcher.
#
# Only run this after the launcher is installed and you have opened it once and it works.
# Anything built into the Google TV home screen goes away until you run
# ./scripts/restore.sh. Nothing is uninstalled.
set -euo pipefail

PKG="dev.lumen.launcher"

if ! adb shell pm path "$PKG" | grep -q "^package:"; then
  echo "The launcher is not installed on the connected device. Run ./scripts/install.sh first." >&2
  exit 1
fi

adb shell cmd package set-home-activity "$PKG/.MainActivity" || true
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith || true
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null

echo "Stock home screen disabled. To undo: ./scripts/restore.sh"
