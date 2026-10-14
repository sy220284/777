#!/usr/bin/env bash
set -euo pipefail

# Uses the debug APK already installed by the device lane; no rebuild or test bypass.
package="com.sy220284.dshmobile.debug"
activity="com.labteto.dshmobile.ui.screens.local.ReadmeScreenshotActivity"
output_dir=".ci/fluid-ui"
mkdir -p "$output_dir"
for appearance in light dark matte; do
  for screen in navigation chat work usage quota design-persona-0 design-persona-1 design-persona-2 design-persona-3 design-gallery design-capability; do
    adb logcat -c
    adb shell am force-stop "$package"
    adb shell am start -W -n "$package/$activity" \
      --es readme_screen "$screen" --ez readme_dark "$([[ "$appearance" == dark ]] && echo true || echo false)" \
      --ez readme_matte "$([[ "$appearance" == matte ]] && echo true || echo false)"
    ready=false
    for attempt in $(seq 1 80); do
      if adb logcat -d -s ReadmeScreenshot:I '*:S' | grep -Fq "READY:$screen"; then
        ready=true
        break
      fi
      sleep 0.25
    done
    if [[ "$ready" != true ]]; then
      echo "::error::流体 UI 截图页面未就绪：$appearance/$screen"
      exit 1
    fi
    adb exec-out screencap -p > "$output_dir/$appearance-$screen.png"
    test -s "$output_dir/$appearance-$screen.png"
  done
  for background in purple forest busy; do
    adb logcat -c
    adb shell am force-stop "$package"
    adb shell am start -W -n "$package/$activity" \
      --es readme_screen material-quality --es readme_background "$background" --ez readme_dark "$([[ "$appearance" == dark ]] && echo true || echo false)" \
      --ez readme_matte "$([[ "$appearance" == matte ]] && echo true || echo false)"
    ready=false
    for attempt in $(seq 1 80); do
      if adb logcat -d -s ReadmeScreenshot:I '*:S' | grep -Fq "READY:material-quality"; then
        ready=true
        break
      fi
      sleep 0.25
    done
    if [[ "$ready" != true ]]; then
      echo "::error::流体 UI 截图页面未就绪：$appearance/material-quality/$background"
      exit 1
    fi
    adb exec-out screencap -p > "$output_dir/$appearance-material-quality-$background.png"
    test -s "$output_dir/$appearance-material-quality-$background.png"
  done
done
adb shell am force-stop "$package"
