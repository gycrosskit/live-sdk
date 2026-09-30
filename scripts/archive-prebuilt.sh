#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${VERSION:?Pass the candidate or release version; this script does not upload or tag}"
export GROUP=com.github.gycrosskit.live-sdk
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
# staging 为本脚本独占的生成仓库，先移除旧版本和 SNAPSHOT 历史文件。
rm -rf build/maven
bash gradlew publishAllPublicationsToStagingRepository --max-workers=2
repository=build/maven/com/github/gycrosskit/live-sdk
python3 jitpack-metadata.py "$repository"
python3 scripts/check-maven.py build/maven com.github.gycrosskit.live-sdk "$VERSION" live-sdk,live-core,live-kuikly ios_arm64,ios_x64,ios_simulator_arm64
mkdir -p build/prebuilt
COPYFILE_DISABLE=1 tar -czf build/prebuilt/live-sdk-maven.tar.gz -C build/maven com/github/gycrosskit/live-sdk
shasum -a 256 build/prebuilt/live-sdk-maven.tar.gz > build/prebuilt/live-sdk-maven.tar.gz.sha256
cat build/prebuilt/live-sdk-maven.tar.gz.sha256
