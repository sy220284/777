artifact_name_for_component() {
  printf '777-toolchain-%s-latest\n' "$1"
}

required_components() {
  local profile="$1"
  printf '%s\n' $BUILD_COMPONENTS
  if [ "$profile" = full ]; then
    printf '%s\n' $FULL_EXTRA_COMPONENTS
  fi
}

component_ready() {
  local component="$1" java_home gradle_version node_bin

  case "$component" in
    jdk)
      java_home="$(find_java17_home || true)"
      [ -n "$java_home" ] &&
        [ "$(java_major "$java_home")" = "$JDK_MAJOR" ] &&
        [ -x "$java_home/bin/javac" ]
      ;;
    android-core)
      [ -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ] &&
        [ -x "$SDK_ROOT/platform-tools/adb" ] &&
        { [ -d "$SDK_ROOT/platforms/android-$ANDROID_COMPILE_API" ] ||
          [ -d "$SDK_ROOT/platforms/android-${ANDROID_COMPILE_API}.0" ]; } &&
        [ -d "$SDK_ROOT/build-tools/$ANDROID_BUILD_TOOLS" ]
      ;;
    gradle-runtime)
      if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
        return 1
      fi
      gradle_version="$("$GRADLE_HOME/bin/gradle" --version 2>/dev/null | awk '/^Gradle / {print $2; exit}')"
      [ "$gradle_version" = "$GRADLE_VERSION" ]
      ;;
    gradle-deps)
      [ -d "$GRADLE_USER_HOME/caches/modules-2" ] &&
        [ -d "$GRADLE_USER_HOME/wrapper/dists" ]
      ;;
    runtime-cache)
      [ -n "${DEV777_REPO_ROOT:-}" ] &&
        [ -d "$DEV777_REPO_ROOT/.gradle/runtime-cache" ]
      ;;
    node)
      node_bin="$TOOLS_ROOT/node-current/bin/node"
      if [ -x "$node_bin" ] && [ "$("$node_bin" -p 'process.versions.node')" = "$NODE_VERSION" ]; then
        return 0
      fi
      command -v node >/dev/null 2>&1 &&
        [ "$(node -p 'process.versions.node' 2>/dev/null)" = "$NODE_VERSION" ]
      ;;
    actionlint)
      if [ -x "$TOOLS_ROOT/bin/actionlint" ]; then
        "$TOOLS_ROOT/bin/actionlint" -version 2>/dev/null | grep -Fq "$ACTIONLINT_VERSION"
        return
      fi
      command -v actionlint >/dev/null 2>&1 &&
        actionlint -version 2>/dev/null | grep -Fq "$ACTIONLINT_VERSION"
      ;;
    emulator)
      [ -x "$SDK_ROOT/emulator/emulator" ]
      ;;
    android-image-16)
      find "$SDK_ROOT/system-images" -type d -path '*android-36*google_apis*x86_64' -print -quit 2>/dev/null | grep -q .
      ;;
    android-image-17)
      find "$SDK_ROOT/system-images" -type d -path '*android-37.0*google_apis_ps16k*x86_64' -print -quit 2>/dev/null | grep -q .
      ;;
    *)
      echo "[777-toolchain] 未知组件：$component" >&2
      return 2
      ;;
  esac
}

missing_components() {
  local profile="$1" component
  while IFS= read -r component; do
    [ -n "$component" ] || continue
    component_ready "$component" || printf '%s\n' "$component"
  done < <(required_components "$profile")
}

component_artifacts_json() {
  local profile="$1" first=true component
  printf '['
  while IFS= read -r component; do
    [ -n "$component" ] || continue
    if [ "$first" = true ]; then
      first=false
    else
      printf ','
    fi
    printf '"%s"' "$(artifact_name_for_component "$component")"
  done < <(missing_components "$profile")
  printf ']'
}
