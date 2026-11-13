#!/usr/bin/env bash
# Validate the actual Android test viewport in dp, not a brittle "wm" output spelling.
set -euo pipefail

min_width_dp="${1:-412}"
target_density="${2:-400}"
if ! [[ "$min_width_dp" =~ ^[0-9]+$ && "$target_density" =~ ^[0-9]+$ ]] ||
   (( min_width_dp == 0 || target_density == 0 )); then
  echo "::error::Expected positive integer min_width_dp and target_density" >&2
  exit 2
fi

adb shell wm density "$target_density"
size_output="$(adb shell wm size | tr -d '\r')"
density_output="$(adb shell wm density | tr -d '\r')"
printf 'Android viewport size:\n%s\nDensity:\n%s\n' "$size_output" "$density_output"

# 'wm' prints Physical and/or Override; use the effective (Override-first) value.
width_px="$(printf '%s\n' "$size_output" | awk '
  $1 == "Physical" && $2 == "size:" {split($3,v,"x"); physical=v[1]}
  $1 == "Override" && $2 == "size:" {split($3,v,"x"); forced=v[1]}
  END {print forced ? forced : physical}
')"
density_dpi="$(printf '%s\n' "$density_output" | awk '
  $1 == "Physical" && $2 == "density:" {physical=$3}
  $1 == "Override" && $2 == "density:" {forced=$3}
  END {print forced ? forced : physical}
')"

if ! [[ "$width_px" =~ ^[0-9]+$ && "$density_dpi" =~ ^[0-9]+$ ]] ||
   (( width_px == 0 || density_dpi == 0 )); then
  echo "::error::Unable to parse effective Android display width/density" >&2
  exit 1
fi

width_dp=$((width_px * 160 / density_dpi))
echo "Effective display: ${width_px}px at ${density_dpi}dpi -> ${width_dp}dp"
if (( width_dp < min_width_dp )); then
  echo "::error::Display ${width_dp}dp is narrower than ${min_width_dp}dp; 412dp Compose test windows would be clipped by the device" >&2
  exit 1
fi
