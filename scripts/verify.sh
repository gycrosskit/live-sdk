#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export VERSION="${VERSION:-0.3.0-rc.1}"
bash gradlew :live-core:compileKotlinOhosArm64 :live-kuikly:compileKotlinOhosArm64 :live-core:compileDebugKotlinAndroid :live-core:compileKotlinIosArm64 :live-core:compileKotlinIosSimulatorArm64 :live-kuikly:compileDebugKotlinAndroid :live-kuikly:compileKotlinIosArm64 :live-kuikly:compileKotlinIosSimulatorArm64 :live-core:testDebugUnitTest :live-kuikly:testDebugUnitTest --max-workers=2
for module in ':live-core' ':live-kuikly'; do
  bash gradlew "${module}:publishKotlinMultiplatformPublicationToStagingRepository" "${module}:publishAndroidReleasePublicationToStagingRepository" "${module}:publishIosSimulatorArm64PublicationToStagingRepository" "${module}:publishIosArm64PublicationToStagingRepository" --max-workers=2
done
bash gradlew :live-core:publishOhosArm64PublicationToStagingRepository :live-kuikly:publishOhosArm64PublicationToStagingRepository --max-workers=2
bash gradlew -p verification/consumer "-PliveVersion=$VERSION" -PverifyEmoji -PverifyKuiklyCompose assembleDebug verifyNoCmpUi verifySingleSdk linkDebugFrameworkIosSimulatorArm64 linkDebugFrameworkIosArm64 --refresh-dependencies --max-workers=2
bash gradlew -p verification/consumer "-PliveVersion=$VERSION" -PverifyOhos -PverifyKuiklyCompose compileKotlinOhosArm64 --max-workers=2
