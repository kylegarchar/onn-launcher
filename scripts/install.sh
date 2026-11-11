#!/usr/bin/env bash
# Installs (or updates) the launcher on the connected box and asks Android to use it as Home.
#
#   ./scripts/install.sh                # downloads the latest build from GitHub
#   ./scripts/install.sh my-build.apk   # installs a file you already have
#
# Connect first:  adb connect <BOX_IP>:5555
set -euo pipefail

PKG="dev.lumen.launcher"
APK_URL="https://github.com/kylegarchar/onn-launcher/releases/download/latest/onn-launcher.apk"
APK="${1:-}"

if [ -z "$APK" ]; then
  APK="$(mktemp -t onn-launcher.XXXXXX).apk"
  echo "Downloading latest build..."
  curl -fL --progress-bar -o "$APK" "$APK_URL"
fi

echo "Installing $APK"
adb install -r "$APK"

echo "Setting as Home"
adb shell cmd package set-home-activity "$PKG/.MainActivity" || true
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null

cat <<'EOF'

Done. Press Home on the remote.

If Home still opens the Google TV screen, the stock launcher is overriding the choice.
Run ./scripts/force-default.sh to switch it off (./scripts/restore.sh undoes that).
EOF
