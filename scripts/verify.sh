#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
bash gradlew compileDebugKotlinAndroid compileKotlinIosArm64 compileKotlinIosSimulatorArm64 :live-kuikly:compileDebugKotlinAndroid :live-kuikly:compileKotlinIosArm64 :live-kuikly:compileKotlinIosSimulatorArm64 :live-core:testDebugUnitTest :live-kuikly:testDebugUnitTest --max-workers=2
for module in '' ':live-core' ':live-kuikly'; do
  bash gradlew "${module}:publishKotlinMultiplatformPublicationToStagingRepository" "${module}:publishAndroidReleasePublicationToStagingRepository" "${module}:publishIosSimulatorArm64PublicationToStagingRepository" "${module}:publishIosArm64PublicationToStagingRepository" --max-workers=2
done
bash gradlew -p verification/consumer assembleDebug verifyNoCompose verifySingleSdk linkDebugFrameworkIosSimulatorArm64 linkDebugFrameworkIosArm64 --refresh-dependencies --max-workers=2
bash gradlew -p verification/consumer -PverifyCmp assembleDebug verifySingleSdk compileKotlinIosSimulatorArm64 --max-workers=2
