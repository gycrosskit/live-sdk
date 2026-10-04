#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
output=build/ownership/callbacks
mkdir -p "$output"
swiftc -emit-module -emit-library -module-name AtomicXCore verification/ownership/MocksAtomicX.swift -o "$output/libAtomicXCore.dylib" -emit-module-path "$output/AtomicXCore.swiftmodule"
swiftc -emit-module -emit-library -module-name ImSDK_Plus verification/ownership/MocksImSDK.swift -o "$output/libImSDK_Plus.dylib" -emit-module-path "$output/ImSDK_Plus.swiftmodule"
swiftc -I "$output" -L "$output" -lAtomicXCore -lImSDK_Plus -Xlinker -rpath -Xlinker "$(pwd)/$output" ios/Sources/GycLiveNative/LiveAccountOwnership.swift ios/Sources/GycLiveNative/GycLiveClient+Account.swift verification/ownership/AccountHost.swift verification/ownership/callbacks/main.swift -o "$output/contract"
"$output/contract"
