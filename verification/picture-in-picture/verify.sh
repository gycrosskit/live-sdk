#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
output=build/picture-in-picture
mkdir -p "$output"
swiftc -emit-module -emit-library -module-name UIKit verification/picture-in-picture/MocksUIKit.swift -o "$output/libUIKit.dylib" -emit-module-path "$output/UIKit.swiftmodule"
swiftc -emit-module -emit-library -module-name RTCRoomEngine verification/picture-in-picture/MocksRoomEngine.swift -o "$output/libRTCRoomEngine.dylib" -emit-module-path "$output/RTCRoomEngine.swiftmodule"
swiftc -I "$output" -L "$output" -lUIKit -lRTCRoomEngine -Xlinker -rpath -Xlinker "$(pwd)/$output" ios/Sources/GycLiveNative/LiveMainThread.swift ios/Sources/GycLiveNative/GycLiveClient+PictureInPicture.swift verification/picture-in-picture/main.swift -o "$output/contract"
"$output/contract"
