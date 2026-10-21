#!/usr/bin/env bash
# Builds the Windows app (x64) as a zip: unzip anywhere and run CloudStream.exe. CI runs this in Git Bash on a
# Windows runner. Needs a full JDK 17+ in JAVA_HOME. Usage: macApp/build-windows.sh [version]
set -euo pipefail
cd "$(dirname "$0")/.."

version="${1:-1.0.0}"
vlc=vlc-3.0.24-win64.zip
vlc_sha256=fcf30850371ad10c9373cc4f0f4501e7dee49e3e9ae9f20c72fb2661a1ca6323

# The in-app player needs libVLC; bundling it means users don't have to install VLC.
# Windows' libvlccore.dll looks for plugins next to itself, so they go inside lib (macOS keeps them beside it)
vlc_dir="macApp/resources/windows-x64/vlc"
if [ ! -f "$vlc_dir/lib/libvlccore.dll" ]; then
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/vlc.zip" "https://download.videolan.org/pub/videolan/vlc/3.0.24/win64/$vlc"
  echo "$vlc_sha256  $tmp/vlc.zip" | sha256sum -c
  unzip -q "$tmp/vlc.zip" -d "$tmp"
  mkdir -p "$vlc_dir/lib"
  cp "$tmp"/vlc-3.0.24/libvlc.dll "$tmp"/vlc-3.0.24/libvlccore.dll "$vlc_dir/lib/"
  cp -R "$tmp"/vlc-3.0.24/plugins "$vlc_dir/lib/"
  rm -rf "$tmp"
fi
# VLC's plugin cache is keyed to file times, which copying changes; drop it so libVLC doesn't report it stale
rm -f "$vlc_dir/lib/plugins/plugins.dat"

./gradlew :macApp:createDistributable -PmacAppVersion="$version"

app=macApp/build/compose/binaries/main/app/CloudStream
test -f "$app/CloudStream.exe"
test -n "$(find "$app" -name libvlccore.dll | head -1)"

zip="macApp/build/CloudStream-$version-windows.zip"
rm -f "$zip"
(cd "$(dirname "$app")" && 7z a -tzip -bso0 "$OLDPWD/$zip" CloudStream)
echo "$zip"
