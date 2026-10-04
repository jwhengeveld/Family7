#!/usr/bin/env bash
# Haalt de Google Cast iOS Sender SDK op (alleen als xcframework te krijgen,
# niet via Swift Package Manager). Het archief is 44 MB en blijft buiten git;
# de SHA-256 hieronder zorgt dat er precies deze versie wordt gebruikt.
set -euo pipefail

VERSION="4.8.6"
SHA256="e1fe7fd6f2bf4b58e830d378fafc435d159bd755f7ee20fd3033aa1ce313cd6e"
URL="https://dl.google.com/dl/chromecast/sdk/ios/GoogleCastSDK-ios-${VERSION}_static.zip"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="$ROOT/Vendor/GoogleCast.xcframework"

if [ -d "$TARGET" ] && [ "$(cat "$ROOT/Vendor/.cast-sdk-version" 2>/dev/null)" = "$VERSION" ]; then
  echo "Google Cast SDK $VERSION staat al klaar."
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "Google Cast SDK $VERSION downloaden..."
curl -fsSL "$URL" -o "$TMP/cast.zip"
echo "$SHA256  $TMP/cast.zip" | shasum -a 256 -c -

unzip -q "$TMP/cast.zip" -d "$TMP"
rm -rf "$TARGET"
mkdir -p "$ROOT/Vendor"
cp -R "$TMP/GoogleCastSDK-ios-${VERSION}_static_xcframework/GoogleCast.xcframework" "$TARGET"
# macOS op een exFAT-schijf laat ._-bestanden achter die Xcode in de war brengen.
find "$ROOT/Vendor" -name '._*' -delete
echo "$VERSION" > "$ROOT/Vendor/.cast-sdk-version"
echo "Klaar: $TARGET"
