#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${LIVE_KUIKLY_IOS_FRAMEWORK_DIR:?Pass parent directory of the real OpenKuiklyIOSRender.framework (iphoneos)}"
framework="verification/consumer/build/bin/iosArm64/debugFramework"
test -f "$framework/LiveKuikly.framework/LiveKuikly"
test -f "$LIVE_KUIKLY_IOS_FRAMEWORK_DIR/OpenKuiklyIOSRender.framework/OpenKuiklyIOSRender"
mkdir -p build/native-ios
sdk="$(xcrun --sdk iphoneos --show-sdk-path)"
xcrun --sdk iphoneos swiftc -sdk "$sdk" -target arm64-apple-ios15.0 -F "$framework" -F "$LIVE_KUIKLY_IOS_FRAMEWORK_DIR" -typecheck live-kuikly/ios/GycLiveView.swift
xcrun --sdk iphoneos swiftc -sdk "$sdk" -target arm64-apple-ios15.0 -F "$framework" -F "$LIVE_KUIKLY_IOS_FRAMEWORK_DIR" live-kuikly/ios/GycLiveView.swift verification/ios/Main.swift -framework LiveKuikly -framework OpenKuiklyIOSRender -framework UIKit -framework Foundation -Xlinker -ObjC -o build/native-ios/live-consumer
