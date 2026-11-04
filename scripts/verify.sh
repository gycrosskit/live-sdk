#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export VERSION="${VERSION:-1.1.3}"
bash gradlew :live-core:compileKotlinOhosArm64 :live-kuikly:compileKotlinOhosArm64 :live-core:compileDebugKotlinAndroid :live-core:compileKotlinIosArm64 :live-core:compileKotlinIosSimulatorArm64 :live-kuikly:compileDebugKotlinAndroid :live-kuikly:compileKotlinIosArm64 :live-kuikly:compileKotlinIosSimulatorArm64 :live-core:testDebugUnitTest :live-kuikly:testDebugUnitTest :live-room-core:compileKotlinOhosArm64 :live-room-core:compileDebugKotlinAndroid :live-room-core:compileKotlinIosArm64 :live-room-core:compileKotlinIosSimulatorArm64 :live-room-core:testDebugUnitTest :live-room-core:jvmTest --max-workers=2
for module in ':live-core' ':live-kuikly' ':live-room-core'; do
  bash gradlew "${module}:publishKotlinMultiplatformPublicationToStagingRepository" "${module}:publishAndroidReleasePublicationToStagingRepository" "${module}:publishIosSimulatorArm64PublicationToStagingRepository" "${module}:publishIosArm64PublicationToStagingRepository" --max-workers=2
done
bash gradlew :live-core:publishOhosArm64PublicationToStagingRepository :live-kuikly:publishOhosArm64PublicationToStagingRepository :live-room-core:publishOhosArm64PublicationToStagingRepository :live-room-core:publishJvmPublicationToStagingRepository :live-room-core:publishIosX64PublicationToStagingRepository --max-workers=2
bash gradlew -p verification/consumer "-PliveVersion=$VERSION" -PverifyEmoji -PverifyKuiklyCompose assembleDebug minifyReleaseWithR8 verifyNoCmpUi verifySingleSdk linkDebugFrameworkIosSimulatorArm64 linkDebugFrameworkIosArm64 --refresh-dependencies --max-workers=2
bash gradlew -p verification/consumer "-PliveVersion=$VERSION" -PverifyOhos -PverifyKuiklyCompose compileKotlinOhosArm64 --max-workers=2
# 独立房间产物必须在无 SDK/Kuikly 的消费者中验证，不能由 Live SDK 消费代替。
bash gradlew -p verification/room-consumer "-PliveVersion=$VERSION" "-PliveMavenRepo=$PWD/build/maven" jvmTest assembleDebug verifyNoTencentSdk compileKotlinOhosArm64 linkDebugFrameworkIosSimulatorArm64 linkDebugFrameworkIosX64 compileKotlinIosArm64 --max-workers=2
