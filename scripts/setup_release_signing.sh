#!/usr/bin/env bash
# One-time setup: generate a release keystore and print GitHub secret commands.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KEYSTORE="$ROOT/release.keystore"
ALIAS="charuco-release"
STORE_PASS="${RELEASE_STORE_PASSWORD:-$(openssl rand -base64 24)}"
KEY_PASS="${RELEASE_KEY_PASSWORD:-$STORE_PASS}"

if [[ -f "$KEYSTORE" ]]; then
  echo "release.keystore already exists at $KEYSTORE"
  echo "Delete it first if you want to regenerate."
  exit 1
fi

keytool -genkeypair -v \
  -storetype PKCS12 \
  -keystore "$KEYSTORE" \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass "$STORE_PASS" \
  -keypass "$KEY_PASS" \
  -dname "CN=Charuco Calibrator, OU=Research, O=sal0-h, C=US"

cat > "$ROOT/keystore.properties" <<EOF
storeFile=release.keystore
storePassword=$STORE_PASS
keyPassword=$KEY_PASS
keyAlias=$ALIAS
EOF

B64="$ROOT/release-keystore.base64.txt"
base64 -w0 "$KEYSTORE" > "$B64"

echo ""
echo "Created $KEYSTORE and keystore.properties (gitignored)."
echo ""
echo "GitHub Actions secrets (run from repo root with gh auth):"
echo "  gh secret set ANDROID_KEYSTORE_BASE64 < \"$B64\""
echo "  gh secret set ANDROID_KEYSTORE_PASSWORD -b \"$STORE_PASS\""
echo "  gh secret set ANDROID_KEY_PASSWORD -b \"$KEY_PASS\""
echo "  gh secret set ANDROID_KEY_ALIAS -b \"$ALIAS\""
echo ""
echo "Local signed release: ./gradlew assembleRelease"
echo "Tag a release: git tag v1.0.0 && git push origin v1.0.0"
