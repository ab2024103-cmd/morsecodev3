#!/usr/bin/env bash
#
# §19.3 — create the ONE stable upload key and load it into GitHub Actions.
#
# Run this on YOUR machine, not in CI. The key it makes is the app's permanent
# identity: every future update of Morsecode must be signed with the same file,
# so back it up somewhere you will still have in five years. There is no
# recovery if it is lost — Google Play will refuse an update signed by a
# different key, and a sideloaded update will refuse to install over the old
# one.
#
# The specification forbids throwaway keys (§19.3), which is exactly why this
# script does not run in the build: a key minted by CI would be a new identity
# on every run.
#
# Usage:
#   bash tools/make-upload-key.sh                 # create + print the commands
#   bash tools/make-upload-key.sh --set-secrets   # create + set them via gh
#
set -euo pipefail

KEYSTORE="${KEYSTORE:-morsecode-upload.jks}"
ALIAS="${ALIAS:-morsecode}"
VALIDITY_DAYS="${VALIDITY_DAYS:-10950}"   # 30 years; Play requires >= 25
REPO="${REPO:-ab2024103-cmd/morsecodev3}"

if [ -f "$KEYSTORE" ]; then
  echo "refusing to overwrite $KEYSTORE — that file IS the app's identity" >&2
  exit 1
fi

command -v keytool >/dev/null || { echo "keytool not found: install a JDK" >&2; exit 1; }

# A generated password is stronger than one typed twice, and it never appears
# in shell history.
STORE_PASSWORD="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32)"
KEY_PASSWORD="$STORE_PASSWORD"

keytool -genkeypair \
  -keystore "$KEYSTORE" \
  -storetype PKCS12 \
  -storepass "$STORE_PASSWORD" \
  -keypass "$KEY_PASSWORD" \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 \
  -validity "$VALIDITY_DAYS" \
  -dname "CN=Morsecode, OU=Morsecode, O=Morsecode, L=, ST=, C=" >/dev/null

echo "created $KEYSTORE (alias: $ALIAS)"
echo
echo "SHA-256 fingerprint — CI prints this on every signed build, so you can"
echo "confirm the artifact really came from this key:"
keytool -list -v -keystore "$KEYSTORE" -storepass "$STORE_PASSWORD" -alias "$ALIAS" \
  | sed -n 's/^[[:space:]]*SHA256:/  SHA256:/p'
echo

KEYSTORE_B64="$(base64 -w0 "$KEYSTORE" 2>/dev/null || base64 "$KEYSTORE" | tr -d '\n')"

if [ "${1:-}" = "--set-secrets" ]; then
  command -v gh >/dev/null || { echo "gh not found" >&2; exit 1; }
  printf '%s' "$KEYSTORE_B64"   | gh secret set KEYSTORE_B64   --repo "$REPO"
  printf '%s' "$ALIAS"          | gh secret set KEY_ALIAS      --repo "$REPO"
  printf '%s' "$KEY_PASSWORD"   | gh secret set KEY_PASSWORD   --repo "$REPO"
  printf '%s' "$STORE_PASSWORD" | gh secret set STORE_PASSWORD --repo "$REPO"
  echo "four secrets set on $REPO — the next push produces a signed release APK"
else
  SECRETS_FILE="upload-key-secrets.txt"
  umask 077
  {
    echo "KEY_ALIAS=$ALIAS"
    echo "STORE_PASSWORD=$STORE_PASSWORD"
    echo "KEY_PASSWORD=$KEY_PASSWORD"
    echo
    echo "KEYSTORE_B64 (one line):"
    echo "$KEYSTORE_B64"
  } > "$SECRETS_FILE"
  echo "wrote $SECRETS_FILE (mode 600). Either paste these four values into"
  echo "GitHub → Settings → Secrets and variables → Actions, or run:"
  echo
  echo "  bash tools/make-upload-key.sh --set-secrets"
  echo
  echo "Then delete $SECRETS_FILE and keep $KEYSTORE somewhere safe."
fi

cat <<'NOTE'

Do NOT commit the .jks or the secrets file. Both are already ignored by
.gitignore; keep it that way (§17.1: nothing about this app leaves your control).
NOTE
