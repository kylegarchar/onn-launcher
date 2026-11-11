#!/usr/bin/env bash
# Puts the box back the way it shipped: re-enables the Google TV home screen and,
# unless you pass --keep, removes this launcher.
#
#   ./scripts/restore.sh          # stock home screen back, launcher uninstalled
#   ./scripts/restore.sh --keep   # stock home screen back, launcher left installed
set -euo pipefail

PKG="dev.lumen.launcher"

adb shell pm enable com.google.android.apps.tv.launcherx
adb shell pm enable com.google.android.tungsten.setupwraith || true

if [ "${1:-}" != "--keep" ]; then
  adb uninstall "$PKG" || true
fi

adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null || true
echo "Restored. Press Home on the remote; reboot the box if the old screen does not appear."
