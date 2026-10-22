plugins {
 kotlin("multiplatform") version "2.2.21-1.0.0"
 kotlin("plugin.compose") version "2.2.21-1.0.0" apply false
 id("com.android.application") version "8.10.1"
}
val kuiklyRenderFrameworkDir = providers.gradleProperty("kuiklyRenderFrameworkDir").orNull
val liveVersion = providers.gradleProperty("liveVersion").orElse("0.3.0-rc.1").get()
val verifyOhos = providers.gradleProperty("verifyOhos").isPresent
val verifyEmoji = verifyOhos || providers.gradleProperty("verifyEmoji").isPresent
val verifyLocalSource = providers.gradleProperty("verifyLocalSource").isPresent
check(!verifyLocalSource || !providers.gradleProperty("remoteOnly").isPresent) { "Local source and remote-only validation are separate modes" }
val verifyFrame = providers.gradleProperty("verifyFrame").isPresent
val verifyKuiklyCompose = providers.gradleProperty("verifyKuiklyCompose").isPresent
if (verifyKuiklyCompose) apply(plugin = "org.jetbrains.kotlin.plugin.compose")
kotlin {
 if (verifyOhos) ohosArm64()
 androidTarget { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }
 iosX64 {
  binaries.framework {
   baseName = "LiveKuikly"
   kuiklyRenderFrameworkDir?.let { linkerOpts("-F$it", "-framework", "OpenKuiklyIOSRender") }
   isStatic = true
   if (!verifyOhos) export("com.github.gycrosskit.live-sdk:live-kuikly:$liveVersion")
   export("com.github.gycrosskit.live-sdk:live-core:$liveVersion")
  }
 }
 iosSimulatorArm64 {
  binaries.framework {
   baseName = "LiveKuikly"
   kuiklyRenderFrameworkDir?.let { linkerOpts("-F$it", "-framework", "OpenKuiklyIOSRender") }
   isStatic = true
   if (!verifyOhos) export("com.github.gycrosskit.live-sdk:live-kuikly:$liveVersion")
   export("com.github.gycrosskit.live-sdk:live-core:$liveVersion")
  }
 }
 iosArm64 {
  binaries.framework {
   baseName = "LiveKuikly"
   kuiklyRenderFrameworkDir?.let { linkerOpts("-F$it", "-framework", "OpenKuiklyIOSRender") }
   isStatic = true
   if (!verifyOhos) export("com.github.gycrosskit.live-sdk:live-kuikly:$liveVersion")
   export("com.github.gycrosskit.live-sdk:live-core:$liveVersion")
  }
 }
 sourceSets.commonMain.get().apply { if (verifyKuiklyCompose) kotlin.srcDir("src/kuiklyComposeMain/kotlin") }
 sourceSets.commonMain.get().kotlin.srcDir("src/kuiklyMain/kotlin")
 if (verifyEmoji) sourceSets.commonMain.get().kotlin.srcDir("src/emojiConsumerMain/kotlin")
 sourceSets.androidMain.get().kotlin.srcDir("src/kuiklyAndroidMain/kotlin")
 sourceSets.commonMain.dependencies {
  api("com.github.gycrosskit.live-sdk:live-kuikly:$liveVersion")
  api("com.github.gycrosskit.live-sdk:live-core:$liveVersion")
 }
 if (verifyFrame) {
  sourceSets.androidUnitTest {
   // 复用真实布局/生命周期契约，生产实现仍只从远程 AAR 解析。
   kotlin.srcDir("../../live-kuikly/src/androidUnitTest/kotlin")
   kotlin.include("**/KuiklyLiveLayoutTest.kt", "**/KuiklyLiveLifecycleTest.kt")
   dependencies {
    implementation(kotlin("test-junit"))
    implementation("org.robolectric:robolectric:4.16.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
   }
  }
 }
}
android {
 namespace = "io.github.gycrosskit.liveconsumer"
 compileSdk = 36
 defaultConfig { applicationId = "io.github.gycrosskit.liveconsumer"; minSdk = 24; targetSdk = 36 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
}
tasks.register("verifyNoCmpUi") {
    doLast {
        val resolution = configurations.getByName("debugRuntimeClasspath").incoming.resolutionResult
        check(resolution.allDependencies.none { it is org.gradle.api.artifacts.result.UnresolvedDependencyResult }) { "Runtime dependencies must resolve before checking UI isolation" }
        val deps = resolution.allComponents.mapNotNull { it.moduleVersion }
        val forbidden = deps.filter {
            it.group.startsWith("org.jetbrains.compose.ui") || it.group.startsWith("org.jetbrains.compose.foundation") ||
                it.group.startsWith("org.jetbrains.compose.material") || it.group.startsWith("androidx.compose.ui") ||
                it.group.startsWith("androidx.compose.foundation") || it.group.startsWith("androidx.compose.material")
        }
        check(forbidden.isEmpty()) { "Kuikly consumer pulls a second CMP UI: $forbidden" }
        if (verifyKuiklyCompose) check(deps.any { it.group == "com.tencent.kuikly-open" && it.name.startsWith("compose") })
        println("PASS no second CMP UI (KuiklyCompose presence required when API probe enabled); Compose runtime: " + deps.filter { it.group.contains("compose.runtime") })
    }
}

tasks.register("verifySingleSdk") {
 doLast {
  val runtime = configurations.getByName("debugRuntimeClasspath")
  val resolution = runtime.incoming.resolutionResult
  check(resolution.allDependencies.none { it is org.gradle.api.artifacts.result.UnresolvedDependencyResult }) { "Runtime dependencies must resolve before checking product identity" }
  // 检查组件图，避免 source consumer 在 Android 多种输出文件间做无类型选择。
  val deps = resolution.allComponents.mapNotNull { it.moduleVersion }
  val liveArtifacts = deps.filter { it.group == "com.github.gycrosskit.live-sdk" }
  check(liveArtifacts.isNotEmpty() && liveArtifacts.all { it.version == liveVersion })
  if (verifyLocalSource) {
   val liveComponents = runtime.incoming.resolutionResult.allComponents.filter {
    it.moduleVersion?.group == "com.github.gycrosskit.live-sdk"
   }
   check(liveComponents.size == 2 && liveComponents.all { it.id is org.gradle.api.artifacts.component.ProjectComponentIdentifier }) {
    "Local validation must consume both source projects, without published Live artifacts: $liveComponents"
   }
   check(liveComponents.map { (it.id as org.gradle.api.artifacts.component.ProjectComponentIdentifier).projectPath }.toSet() == setOf(":live-core", ":live-kuikly"))
  } else {
   check(liveArtifacts.map { it.name }.toSet() == setOf("live-core", "live-core-android", "live-kuikly", "live-kuikly-android")) {
    "Published validation requires exactly the two Live products: $liveArtifacts"
   }
  }
  println("Exact Live components: " + liveArtifacts.joinToString())
  check(deps.count { it.name == "atomicx-core" } == 1)
 }
}
