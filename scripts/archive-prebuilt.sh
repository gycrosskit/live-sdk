#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${VERSION:?Pass the candidate or release version; this script does not upload or tag}"
export GROUP=com.github.gycrosskit.live-sdk
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
# staging 为本脚本独占的生成仓库，先移除旧版本和 SNAPSHOT 历史文件。
rm -rf build/maven
bash gradlew --no-daemon --max-workers=1 -Dorg.gradle.parallel=false publishAllPublicationsToStagingRepository
repository=build/maven/com/github/gycrosskit/live-sdk
python3 jitpack-metadata.py "$repository"
python3 scripts/check-maven.py build/maven com.github.gycrosskit.live-sdk "$VERSION" live-sdk,live-core,live-kuikly ios_arm64,ios_x64,ios_simulator_arm64 live-sdk,live-sdk-android,live-sdk-iosarm64,live-sdk-iosx64,live-sdk-iossimulatorarm64,live-core,live-core-android,live-core-iosarm64,live-core-iosx64,live-core-iossimulatorarm64,live-kuikly,live-kuikly-android,live-kuikly-iosarm64,live-kuikly-iosx64,live-kuikly-iossimulatorarm64
mkdir -p build/prebuilt
COPYFILE_DISABLE=1 tar --no-xattrs -czf build/prebuilt/live-sdk-maven.tar.gz -C build/maven com/github/gycrosskit/live-sdk
(
  cd build/prebuilt
  shasum -a 256 live-sdk-maven.tar.gz > live-sdk-maven.tar.gz.sha256
  cat live-sdk-maven.tar.gz.sha256
)
