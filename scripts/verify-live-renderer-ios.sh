#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
consumer="build/native-renderer-consumer"
mkdir -p "$consumer"
cp verification/renderer-ios/RendererTests.swift verification/renderer-ios/Podfile "$consumer/"
ruby_bin="${LIVE_NATIVE_RUBY:-ruby}"
ruby_env=()
if ! "$ruby_bin" -rxcodeproj -e '' 2>/dev/null; then
  ruby_bin="$(brew --prefix ruby)/bin/ruby"
  ruby_env=(env "GEM_HOME=$(brew --prefix cocoapods)/libexec")
fi
"${ruby_env[@]}" "$ruby_bin" - "$consumer" <<'RUBY'
require 'xcodeproj'
root = ARGV.fetch(0)
project = Xcodeproj::Project.new(File.join(root, 'LiveRendererConsumer.xcodeproj'))
target = project.new_target(:unit_test_bundle, 'LiveRendererTests', :ios, '15.0')
target.source_build_phase.add_file_reference(project.main_group.new_file('RendererTests.swift'))
target.build_configurations.each do |config|
  config.build_settings['SWIFT_VERSION'] = '5.9'
  config.build_settings['PRODUCT_BUNDLE_IDENTIFIER'] = 'io.github.gycrosskit.live-renderer-tests'
  config.build_settings['GENERATE_INFOPLIST_FILE'] = 'YES'
  config.build_settings['CODE_SIGNING_ALLOWED'] = 'NO'
end
project.save
scheme = Xcodeproj::XCScheme.new
scheme.add_build_target(target)
scheme.add_test_target(target)
scheme.save_as(project.path, 'LiveRendererTests')
RUBY
pod install --project-directory="$consumer"
python3 verification/renderer-ios/test_contract.py --lock "$consumer/Podfile.lock"
device="$(xcrun simctl list devices available --json | python3 -c '
import json, sys
for runtime, devices in json.load(sys.stdin)["devices"].items():
    if "iOS" in runtime:
        for device in devices:
            if device["name"].startswith("iPhone"):
                print(device["udid"])
                sys.exit(0)
sys.exit("No available iPhone Simulator")')"
xcodebuild -workspace "$consumer/LiveRendererConsumer.xcworkspace" \
  -scheme LiveRendererTests -configuration Debug -sdk iphonesimulator \
  -destination "id=$device" -derivedDataPath "$consumer/DerivedData" \
  -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO test
# 给现有 LiveKuikly 最终链接入口提供真实 Pod 的 iphoneos Framework。
xcodebuild -project "$consumer/Pods/Pods.xcodeproj" -target GycLiveNative \
  -configuration Debug -sdk iphoneos \
  CONFIGURATION_BUILD_DIR="$PWD/$consumer/iphoneos" CODE_SIGNING_ALLOWED=NO build
