#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
consumer="build/native-live-consumer"
mkdir -p "$consumer"
cp verification/native-ios/Main.swift verification/native-ios/Podfile "$consumer/"
ruby_bin="${LIVE_NATIVE_RUBY:-ruby}"
ruby_env=()
if ! "$ruby_bin" -rxcodeproj -e '' 2>/dev/null; then
  # Homebrew CocoaPods 的 xcodeproj gem 不在 macOS 系统 Ruby 中。
  ruby_bin="$(brew --prefix ruby)/bin/ruby"
  ruby_env=(env "GEM_HOME=$(brew --prefix cocoapods)/libexec")
fi
"${ruby_env[@]}" "$ruby_bin" - "$consumer" <<'RUBY'
require 'xcodeproj'
root = ARGV.fetch(0)
project = Xcodeproj::Project.new(File.join(root, 'LiveNativeConsumer.xcodeproj'))
target = project.new_target(:application, 'LiveNativeConsumer', :ios, '15.0')
source = project.main_group.new_file('Main.swift')
target.source_build_phase.add_file_reference(source)
target.build_configurations.each do |config|
  config.build_settings['SWIFT_VERSION'] = '5.9'
  config.build_settings['PRODUCT_BUNDLE_IDENTIFIER'] = 'io.github.gycrosskit.live-native-consumer'
  config.build_settings['GENERATE_INFOPLIST_FILE'] = 'YES'
  config.build_settings['CODE_SIGNING_ALLOWED'] = 'NO'
end
project.save
scheme = Xcodeproj::XCScheme.new
scheme.add_build_target(target)
scheme.set_launch_target(target)
scheme.save_as(project.path, 'LiveNativeConsumer')
RUBY
pod install --project-directory="$consumer"
xcodebuild -workspace "$consumer/LiveNativeConsumer.xcworkspace" \
  -scheme LiveNativeConsumer -configuration Debug -sdk iphoneos \
  -destination 'generic/platform=iOS' -derivedDataPath "$consumer/DerivedData" \
  CODE_SIGNING_ALLOWED=NO build
binary="$consumer/DerivedData/Build/Products/Debug-iphoneos/LiveNativeConsumer.app/LiveNativeConsumer"
test -f "$binary"
# 新版 Xcode 可把 Debug App 的真实 Swift 代码放到 debug.dylib，入口 stub 本身不代表依赖检查。
for product in "$binary" "$binary.debug.dylib"; do
  if [[ ! -f "$product" ]]; then continue; fi
  xcrun otool -L "$product"
  if xcrun otool -L "$product" | rg 'Shared|LiveKuikly|Compose|OpenKuikly'; then
    echo 'Unexpected UI/KMP runtime dependency' >&2
    exit 1
  fi
done
