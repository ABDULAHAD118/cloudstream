#!/usr/bin/env bash
# Builds CloudStream.app and its DMG for this Mac's architecture. CI runs this same script on each runner.
# Needs a full JDK 17+ (with jpackage) in JAVA_HOME. Usage: macApp/build-dmg.sh [version]
set -euo pipefail
cd "$(dirname "$0")/.."

version="${1:-1.0.0}"
arch="$(uname -m)"
case "$arch" in
  x86_64) label=intel; resources=macos-x64
    vlc=vlc-3.0.24-intel64.dmg; vlc_sha256=1ef6c903e2dc026d4e58ddf7b2a9ed0eb6f8caf80a9a9f46f96490340b962707 ;;
  arm64) label=apple-silicon; resources=macos-arm64
    vlc=vlc-3.0.24-arm64.dmg; vlc_sha256=64a89d93cdd30b0e97131743e246373db82d6826ea882d265d46d74b136da2b7 ;;
  *) echo "Unsupported architecture: $arch" >&2; exit 1 ;;
esac

if [ ! -x "${JAVA_HOME:-}/bin/jpackage" ]; then
  echo "JAVA_HOME must point to a JDK 17+ that includes jpackage (for example Temurin)." >&2
  exit 1
fi

# The in-app player needs libVLC; bundling it means users don't have to install VLC
vlc_dir="macApp/resources/$resources/vlc"
if [ ! -f "$vlc_dir/lib/libvlccore.dylib" ]; then
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/vlc.dmg" "https://download.videolan.org/pub/videolan/vlc/3.0.24/macosx/$vlc"
  echo "$vlc_sha256  $tmp/vlc.dmg" | shasum -a 256 -c
  mnt="$(hdiutil attach -nobrowse -readonly "$tmp/vlc.dmg" | tail -1 | awk -F'\t' '{print $NF}')"
  mkdir -p "$vlc_dir"
  cp -R "$mnt/VLC.app/Contents/MacOS/lib" "$mnt/VLC.app/Contents/MacOS/plugins" "$vlc_dir/"
  hdiutil detach -quiet "$mnt"
  rm -rf "$tmp"
fi

# VLC's plugin cache is keyed to file times, which copying changes; drop it so libVLC doesn't report it stale
rm -f "$vlc_dir/plugins/plugins.dat"

./gradlew :macApp:createDistributable -PmacAppVersion="$version"

app=macApp/build/compose/binaries/main/app/CloudStream.app
lipo "$app/Contents/MacOS/CloudStream" -verify_arch "$arch"
lipo "$app/Contents/runtime/Contents/Home/lib/libjli.dylib" -verify_arch "$arch"
lipo "$(find "$app" -name libvlccore.dylib | head -1)" -verify_arch "$arch"

# Apple Silicon refuses to launch unsigned code, so sign ad-hoc (no Apple Developer account needed)
codesign --force --deep --sign - "$app"
codesign --verify --deep --strict "$app"

dmg="macApp/build/CloudStream-$version-$label.dmg"
root="$(mktemp -d)"
cp -R "$app" "$root/"
ln -s /Applications "$root/Applications"
hdiutil create -quiet -volname CloudStream -srcfolder "$root" -ov -format UDZO "$dmg"
rm -rf "$root"
echo "$dmg"
