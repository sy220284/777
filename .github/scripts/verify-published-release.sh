#!/usr/bin/env bash
set -euo pipefail

tag="${1:?usage: verify-published-release.sh <tag> <sha> <version>}"
sha="${2:?missing release SHA}"
version="${3:?missing version}"
repo="${GITHUB_REPOSITORY:?missing repository identity}"
[[ "$tag" == "v$version" ]] || { echo "::error::Tag and version differ" >&2; exit 1; }
release="$(gh api "repos/$repo/releases/tags/$tag")"
jq -e --arg tag "$tag" --arg sha "$sha" '
  .tag_name == $tag and .target_commitish == $sha and
  .draft == false and .prerelease == false
' <<< "$release" >/dev/null
temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT
gh release download "$tag" --dir "$temp"
for file in app-release.apk update-manifest.json SHA256SUMS.txt; do test -s "$temp/$file"; done
(cd "$temp" && sha256sum --check --strict SHA256SUMS.txt)
apk_size="$(stat -c %s "$temp/app-release.apk")"
apk_sha="$(sha256sum "$temp/app-release.apk" | cut -d' ' -f1)"
jq -e --arg version "$version" --arg sha "$apk_sha" --argjson size "$apk_size" '
  .schema == 1 and .targetVersion == $version and
  .targetApk.name == "app-release.apk" and
  .targetApk.sha256 == $sha and .targetApk.size == $size and
  (.patches | type) == "array"
' "$temp/update-manifest.json" >/dev/null
jq -r '.assets[].name' <<< "$release" | sort > "$temp/remote-assets.txt"
find "$temp" -maxdepth 1 -type f -printf '%f\n' |
  grep -Ev '^(remote-assets.txt|downloaded-assets.txt|checksummed-assets.txt|expected-checksums.txt)$' | sort > "$temp/downloaded-assets.txt"
diff -u "$temp/remote-assets.txt" "$temp/downloaded-assets.txt"
awk '{print $2}' "$temp/SHA256SUMS.txt" | sed 's#^\*##' | sort > "$temp/checksummed-assets.txt"
grep -v -Fx 'SHA256SUMS.txt' "$temp/remote-assets.txt" > "$temp/expected-checksums.txt"
diff -u "$temp/expected-checksums.txt" "$temp/checksummed-assets.txt"
while IFS=$'\t' read -r name size digest; do
  [[ -n "$name" ]] || continue
  test -s "$temp/$name"
  [[ "$(stat -c %s "$temp/$name")" == "$size" ]]
  [[ "$(sha256sum "$temp/$name" | cut -d' ' -f1)" == "$digest" ]]
done < <(jq -r '.patches[] | [.asset, (.size|tostring), .sha256] | @tsv' "$temp/update-manifest.json")
while IFS=$'\t' read -r name digest; do
  [[ -n "$name" && -n "$digest" ]] || exit 1
  [[ "$(sha256sum "$temp/$name" | cut -d' ' -f1)" == "${digest#sha256:}" ]] || {
    echo "::error::Remote GitHub asset digest mismatch: $name" >&2; exit 1;
  }
done < <(jq -r '.assets[] | select(.digest != null and .digest != "") | [.name, .digest] | @tsv' <<< "$release")
echo "Published release remote artifacts verified: $tag ($sha)"
