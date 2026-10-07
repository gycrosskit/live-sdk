#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
live_version="${VERSION:-0.2.1-rc.10}"
bash gradlew :live-core:compileKotlinOhosArm64 compileDebugKotlinAndroid compileKotlinIosArm64 compileKotlinIosSimulatorArm64 :live-kuikly:compileDebugKotlinAndroid :live-kuikly:compileKotlinIosArm64 :live-kuikly:compileKotlinIosSimulatorArm64 :live-core:testDebugUnitTest :live-kuikly:testDebugUnitTest --max-workers=2
for module in '' ':live-core' ':live-kuikly'; do
  bash gradlew "${module}:publishKotlinMultiplatformPublicationToStagingRepository" "${module}:publishAndroidReleasePublicationToStagingRepository" "${module}:publishIosSimulatorArm64PublicationToStagingRepository" "${module}:publishIosArm64PublicationToStagingRepository" --max-workers=2
done
bash gradlew :live-core:publishOhosArm64PublicationToStagingRepository --max-workers=2
bash gradlew -p verification/consumer "-PliveVersion=$live_version" -PverifyEmoji assembleDebug verifyNoCompose verifySingleSdk linkDebugFrameworkIosSimulatorArm64 linkDebugFrameworkIosArm64 --refresh-dependencies --max-workers=2
bash gradlew -p verification/consumer "-PliveVersion=$live_version" -PverifyEmoji -PverifyCmp assembleDebug verifySingleSdk compileKotlinIosSimulatorArm64 --max-workers=2
bash gradlew -p verification/consumer "-PliveVersion=$live_version" -PverifyOhos compileKotlinOhosArm64 --max-workers=2
