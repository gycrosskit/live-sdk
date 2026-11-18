#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
operation="${1:-}"; shift || true
[[ "${1:-}" == --platform && $# -ge 2 ]] || { echo 'Usage: scripts/build.sh compile|package --platform android|ios|harmony|maven' >&2; exit 2; }
platform="$2"; shift 2
if [[ "$platform" == harmony && -n "${HARMONY_CLT_HOME:-}" ]]; then
  export DEVECO_SDK_HOME="$HARMONY_CLT_HOME/sdk"
  export DEVECO_NODE_HOME="$HARMONY_CLT_HOME/tool/node"
  export NODE_HOME="$DEVECO_NODE_HOME"
  export PATH="$NODE_HOME/bin:$HARMONY_CLT_HOME/ohpm/bin:$HARMONY_CLT_HOME/bin:$PATH"
fi
if [[ "$operation" == compile ]]; then
  [[ $# == 0 ]] || { echo 'Unexpected arguments' >&2; exit 2; }
  case "$platform" in
    android) tasks=(:live-core:compileDebugKotlinAndroid :live-kuikly:compileDebugKotlinAndroid :live-room-core:compileDebugKotlinAndroid) ;;
    ios) tasks=(:live-core:compileKotlinIosArm64 :live-core:compileKotlinIosSimulatorArm64 :live-kuikly:compileKotlinIosArm64 :live-kuikly:compileKotlinIosSimulatorArm64 :live-room-core:compileKotlinIosArm64 :live-room-core:compileKotlinIosSimulatorArm64) ;;
    harmony) tasks=(:live-core:compileKotlinOhosArm64 :live-kuikly:compileKotlinOhosArm64 :live-room-core:compileKotlinOhosArm64) ;;
    *) echo 'Unsupported compile platform' >&2; exit 2 ;;
  esac
  [[ ${#tasks[@]} -gt 0 ]] || { echo 'No production target on this platform' >&2; exit 2; }
  args=(--no-daemon --max-workers=1 -Dorg.gradle.parallel=false)
  [[ ! -f scripts/ci-repositories.gradle ]] || args+=(--init-script "$PWD/scripts/ci-repositories.gradle")
  bash gradlew "${args[@]}" "${tasks[@]}"
  if [[ "$platform" == ios ]]; then
    export GYC_IOS_SOURCE="$PWD"
    for native_pod in GycLiveNative; do
      native_output="$PWD/build/native-ios-compile/$native_pod"
      mkdir -p "$native_output"
      export GYC_IOS_POD="$native_pod"
      cat > "$native_output/Podfile" <<'POD'
install! 'cocoapods', :integrate_targets => false
platform :ios, '15.0'
use_frameworks!
target 'NativeCompile' do
  pod 'GycLiveNative/Core', :path => ENV.fetch('GYC_IOS_SOURCE')
  pod 'GycLiveNative/Renderer', :path => ENV.fetch('GYC_IOS_SOURCE')
end
POD
      (cd "$native_output" && pod install)
      native_xcode=(xcodebuild -project "$native_output/Pods/Pods.xcodeproj" -scheme "$native_pod"
        -configuration Debug -sdk iphoneos -destination 'generic/platform=iOS'
        -derivedDataPath "$native_output/DerivedData" CONFIGURATION_BUILD_DIR="$native_output/Products" CODE_SIGNING_ALLOWED=NO)
      "${native_xcode[@]}" build
      "${native_xcode[@]}" -showBuildSettings -json > "$native_output/build-settings.json"
    done
    # Swift 接线直接导入本库生产 Framework，不建立独立消费者工程。
    bash gradlew "${args[@]}" -PliveVerification :live-kuikly:linkDebugFrameworkIosArm64
    ios_sdk="$(xcrun --sdk iphoneos --show-sdk-path)"
    python3 - "$native_output/build-settings.json" "$ios_sdk" "$PWD" <<'SWIFT'
import json
from pathlib import Path
import shlex
import subprocess
import sys

report, sdk, root = sys.argv[1:]
rows = json.loads(Path(report).read_text())
selected = [row['buildSettings'] for row in rows if row.get('target') == 'GycLiveNative']
if len(selected) != 1:
    raise SystemExit('Expected the compiled GycLiveNative build settings')
settings = selected[0]
def flags(key):
    values = shlex.split(settings.get(key, ''))
    values = [value for value in values if value not in ('$(inherited)', '${inherited}')]
    if any('$(' in value or '${' in value for value in values):
        raise SystemExit('Unresolved Xcode setting: ' + key)
    return values
command = ['xcrun', '--sdk', 'iphoneos', 'swiftc', '-sdk', sdk, '-target', 'arm64-apple-ios15.0',
           '-F', root + '/live-kuikly/build/bin/iosArm64/debugFramework',
           '-F', root + '/build/native-ios-compile/GycLiveNative/Products']
for path in flags('FRAMEWORK_SEARCH_PATHS'):
    command += ['-F', path]
for path in flags('HEADER_SEARCH_PATHS'):
    command += ['-Xcc', '-I' + path]
command += flags('OTHER_SWIFT_FLAGS')
command += ['-typecheck', root + '/live-kuikly/ios/GycLiveView.swift']
subprocess.run(command, check=True)
SWIFT
  fi
  if [[ "$platform" == harmony ]]; then exec bash scripts/build.sh package --platform harmony; fi
  exit 0
fi
[[ "$operation" == package ]] || { echo 'Unsupported operation' >&2; exit 2; }
if [[ "$platform" == maven ]]; then exec bash scripts/archive-prebuilt.sh "$@"; fi
[[ "$platform" == harmony ]] || { echo "Unsupported package platform" >&2; exit 2; }
[[ $# == 0 ]] || { echo "Unexpected arguments" >&2; exit 2; }
module=GycLiveNative
cd ohos
if [[ "${HARMONY_DEPENDENCIES_READY:-false}" != true ]]; then "${HARMONY_OHPM:-ohpm}" install --all; fi
hvigor="$(command -v "${HARMONY_HVIGOR:-hvigorw}")"
exec bash "$hvigor" --mode module -p "module=$module@default" -p product=default -p buildMode=release assembleHar --no-daemon
