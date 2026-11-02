#!/usr/bin/env bash
set -euo pipefail

new_apk="${1:?usage: verify-release-identity.sh <new-apk> <version>}"
new_version="${2:?missing new release version}"
repo="${GITHUB_REPOSITORY:?missing repository identity}"
sdk="${ANDROID_SDK_ROOT:?missing Android SDK}"
build_tools="$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
aapt="$build_tools/aapt"
apksigner="$build_tools/apksigner"
test -s "$new_apk"

identity() {
  local apk="$1" certificate badging package code version
  "$apksigner" verify --verbose --print-certs "$apk" > "$RUNNER_TEMP/apk-cert-details.txt"
  certificate="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$RUNNER_TEMP/apk-cert-details.txt" | head -n 1 | tr 'A-F' 'a-f')"
  [[ "$certificate" =~ ^[a-f0-9]{64}$ ]] || { echo "::error::Missing signing certificate digest" >&2; exit 1; }
  badging="$("$aapt" dump badging "$apk")"
  package="$(printf '%s\n' "$badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -n 1)"
  code="$(printf '%s\n' "$badging" | sed -n "s/^package: .*versionCode='\([0-9]*\)'.*/\1/p" | head -n 1)"
  version="$(printf '%s\n' "$badging" | sed -n "s/^package: .*versionName='\([^']*\)'.*/\1/p" | head -n 1)"
  [[ "$package" == "com.sy220284.dshmobile" && "$code" =~ ^[0-9]+$ && -n "$version" ]] || {
    echo "::error::Unexpected release package identity or version metadata" >&2; exit 1;
  }
  printf '%s\t%s\t%s\t%s\n' "$package" "$code" "$version" "$certificate"
}

IFS=$'\t' read -r new_package new_code actual_version new_cert < <(identity "$new_apk")
[[ "$actual_version" == "$new_version" ]] || {
  echo "::error::Release APK version differs from selected GitHub version" >&2; exit 1;
}
old_tag="$(gh api --paginate "repos/$repo/releases?per_page=100" \
  --jq '.[] | select(.draft == false and .prerelease == false) | .tag_name' |
  grep -E '^v[0-9]+\.[0-9]+\.[0-9]+-777\.[0-9]+$' | sort -V | tail -n 1 || true)"
if [[ -z "$old_tag" ]]; then
  echo "First formal release: signature and version checked; no previous APK"
  exit 0
fi
mkdir -p "$RUNNER_TEMP/previous-signed-release"
gh release download "$old_tag" --pattern 'app-release.apk' --dir "$RUNNER_TEMP/previous-signed-release"
old_apk="$RUNNER_TEMP/previous-signed-release/app-release.apk"
test -s "$old_apk"
IFS=$'\t' read -r old_package old_code old_version old_cert < <(identity "$old_apk")
[[ "$old_package" == "$new_package" ]] || { echo "::error::Package name changed" >&2; exit 1; }
[[ "$old_cert" == "$new_cert" ]] || { echo "::error::Signing certificate changed: in-place updates will fail" >&2; exit 1; }
(( 10#$new_code > 10#$old_code )) || { echo "::error::New versionCode is not greater than previous release" >&2; exit 1; }
echo "Signed release upgrade identity verified: $old_tag ($old_code) -> $new_version ($new_code); same certificate"
