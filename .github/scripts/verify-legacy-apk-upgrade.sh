#!/usr/bin/env bash
set -euo pipefail

# Physical adb install -r upgrade, with real package-signature/version validation.
# Usage: verify-legacy-apk-upgrade.sh <older-debug.apk> <newer-debug.apk>
# Both APKs MUST use the same debug signer, applicationId, and debuggable build
# type so that run-as can prove the old private data survived install -r.
# Running two APKs built from the same source does NOT qualify as a legacy upgrade.
if [[ "$#" -ne 2 ]]; then
  echo "usage: $0 <older-debug.apk> <newer-debug.apk>" >&2
  exit 2
fi
old="$1"
new="$2"
test -s "$old" && test -s "$new" || {
  echo "UPGRADE_ERROR: both historical and candidate APK bytes are required" >&2
  exit 1
}
for tool in adb apkanalyzer apksigner sha256sum; do
  command -v "$tool" >/dev/null || { echo "UPGRADE_ERROR: missing $tool" >&2; exit 1; }
done

package_old="$(apkanalyzer manifest application-id "$old")"
package_new="$(apkanalyzer manifest application-id "$new")"
code_old="$(apkanalyzer manifest version-code "$old")"
code_new="$(apkanalyzer manifest version-code "$new")"
[[ -n "$package_old" && "$package_old" == "$package_new" ]] || {
  echo "UPGRADE_ERROR: app IDs differ, cannot test install-over" >&2
  exit 1
}
[[ "$code_old" =~ ^[0-9]+$ && "$code_new" =~ ^[0-9]+$ ]] || {
  echo "UPGRADE_ERROR: invalid version codes" >&2; exit 1;
}
(( code_new >= code_old )) || {
  echo "UPGRADE_ERROR: newer build would be an Android downgrade" >&2
  exit 1
}
sha_old="$(sha256sum "$old" | cut -d' ' -f1)"
sha_new="$(sha256sum "$new" | cut -d' ' -f1)"
[[ "$sha_old" != "$sha_new" ]] || {
  echo "UPGRADE_ERROR: same APK cannot demonstrate a real upgrade" >&2
  exit 1
}
cert_old="$(apksigner verify --print-certs "$old" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1)"
cert_new="$(apksigner verify --print-certs "$new" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1)"
[[ -n "$cert_old" && "$cert_old" == "$cert_new" ]] || {
  echo "UPGRADE_ERROR: signing certificates differ" >&2
  exit 1
}
# Fail closed if the emulator is not fresh; protect existing installed data.
if adb shell pm path "$package_old" 2>/dev/null | grep -q '^package:'; then
  echo "UPGRADE_ERROR: test device already contains $package_old; require a fresh AVD" >&2
  exit 1
fi

echo "UPGRADE_OLD_BUILD package=$package_old code=$code_old sha256=$sha_old"
adb install "$old"
bash .github/scripts/smoke-test-android-startup.sh "$package_old"
# Run-as is intentionally required; unlike a fresh install, install -r must
# preserve this marker inside the old app's private data directory.
adb shell run-as "$package_old" mkdir -p files/local-harness
marker="upgrade-$sha_old-$sha_new"
printf '%s' "$marker" |
  adb shell "run-as $package_old sh -c 'cat > files/local-harness/.upgrade-install-sentinel'"
before="$(adb shell run-as "$package_old" cat files/local-harness/.upgrade-install-sentinel | tr -d '\r')"
[[ "$before" == "$marker" ]] || {
  echo "UPGRADE_ERROR: failed to seed data with historical APK installed" >&2
  exit 1
}

adb shell am force-stop "$package_old"
adb install -r "$new"
bash .github/scripts/smoke-test-android-startup.sh "$package_old"
after="$(adb shell run-as "$package_old" cat files/local-harness/.upgrade-install-sentinel | tr -d '\r')"
[[ "$after" == "$marker" ]] || {
  echo "UPGRADE_ERROR: private app data changed or was erased" >&2
  exit 1
}
echo "UPGRADE_OK package=$package_old old_code=$code_old new_code=$code_new original_private_data_preserved=true"
# This shell gate proves install/identity/private-data survival, not decoded
# session correctness. LocalLegacySqliteMigrationAndroidTest and
# SqliteLegacyZipConversionAndroidTest independently assert decoded history.
