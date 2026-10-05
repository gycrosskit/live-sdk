plugins {
 kotlin("multiplatform") version "2.2.21"
 kotlin("plugin.compose") version "2.2.21" apply false
 id("com.android.application") version "8.10.1"
}
val liveVersion = providers.gradleProperty("liveVersion").orElse("0.2.1-rc.6").get()
if (providers.gradleProperty("verifyCmp").isPresent) pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
kotlin {
 androidTarget { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }
 iosX64()
 iosSimulatorArm64 {
  binaries.framework {
   baseName = "LiveKuikly"
   isStatic = true
   export("com.github.gycrosskit.live-sdk:live-kuikly:$liveVersion")
   export("com.github.gycrosskit.live-sdk:live-core:$liveVersion")
  }
 }
 iosArm64 {
  binaries.framework {
   baseName = "LiveKuikly"
   isStatic = true
   export("com.github.gycrosskit.live-sdk:live-kuikly:$liveVersion")
   export("com.github.gycrosskit.live-sdk:live-core:$liveVersion")
  }
 }
 if (providers.gradleProperty("verifyCmp").isPresent) {
  sourceSets.commonMain.get().kotlin.srcDir("src/cmpConsumerMain/kotlin")
  sourceSets.commonMain.dependencies {
   implementation("com.github.gycrosskit.live-sdk:live-sdk:$liveVersion")
   implementation("org.jetbrains.compose.runtime:runtime:1.10.3")
   implementation("org.jetbrains.compose.ui:ui:1.10.3")
  }
 }
 sourceSets.commonMain.dependencies {
  api("com.github.gycrosskit.live-sdk:live-kuikly:$liveVersion")
  api("com.github.gycrosskit.live-sdk:live-core:$liveVersion")
 }
}
android {
 namespace = "io.github.gycrosskit.liveconsumer"
 compileSdk = 36
 defaultConfig { applicationId = "io.github.gycrosskit.liveconsumer"; minSdk = 24; targetSdk = 36 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
}
tasks.register("verifyNoCompose") {
 doLast {
  val deps = configurations.getByName("debugRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
  check(deps.none { it.moduleVersion.id.group.contains("compose") }) { "Kuikly Native DSL must not pull Compose runtime/UI" }
 }
}

tasks.register("verifySingleSdk") {
 doLast {
  val deps = configurations.getByName("debugRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
  val liveArtifacts = deps.filter { it.moduleVersion.id.group == "com.github.gycrosskit.live-sdk" }
  check(liveArtifacts.isNotEmpty() && liveArtifacts.all { it.moduleVersion.id.version == liveVersion })
  println("Exact Live artifacts: " + liveArtifacts.joinToString { it.moduleVersion.id.toString() })
  check(deps.count { it.moduleVersion.id.name == "live-core-android" } == 1)
  check(deps.count { it.moduleVersion.id.name == "atomicx-core" } == 1)
 }
}
