import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

group = rootProject.group
version = rootProject.version

kotlin {
    androidTarget { publishLibraryVariants("release"); compilerOptions.jvmTarget.set(JvmTarget.JVM_11) }
    jvm { compilerOptions.jvmTarget.set(JvmTarget.JVM_11) }
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    ohosArm64()
    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.dgtang.live.room"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
}
publishing { repositories.maven { name = "staging"; url = uri(rootProject.layout.buildDirectory.dir("maven")) } }
