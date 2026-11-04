plugins {
    kotlin("multiplatform") version "2.2.21-1.0.0"
    id("com.android.library") version "8.10.1"
}
val liveVersion = providers.gradleProperty("liveVersion").orElse("1.1.3").get()
kotlin {
    androidTarget { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
    jvm { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
    iosArm64()
    iosX64 { binaries.framework { baseName = "LiveRoomConsumer"; isStatic = true } }
    iosSimulatorArm64 { binaries.framework { baseName = "LiveRoomConsumer"; isStatic = true } }
    ohosArm64()
    sourceSets {
        commonMain.dependencies { api("com.github.gycrosskit.live-sdk:live-room-core:$liveVersion") }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
android {
    namespace = "io.github.gycrosskit.liveroom.consumer"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
}
tasks.register("verifyNoTencentSdk") {
    doLast {
        val resolution = configurations.getByName("debugRuntimeClasspath").incoming.resolutionResult
        check(resolution.allDependencies.none { it is org.gradle.api.artifacts.result.UnresolvedDependencyResult }) { "All dependencies must resolve before checking SDK isolation" }
        val deps = resolution.allComponents.mapNotNull { it.moduleVersion }
        check(deps.none { it.group.startsWith("io.trtc") || it.group.startsWith("com.tencent") || it.group.contains("compose") }) { "Room-only consumer pulls a vendor SDK or UI runtime: $deps" }
        val live = deps.filter { it.group == "com.github.gycrosskit.live-sdk" }
        check(live.isNotEmpty() && live.all { it.name in setOf("live-room-core", "live-room-core-android") && it.version == liveVersion }) { "Room-only consumer must receive only the room artifact: $live" }
        println("PASS room artifact without Tencent SDK or UI runtime")
    }
}
