plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    `maven-publish`
}
publishing { repositories.maven { name = "staging"; url = uri(rootProject.layout.buildDirectory.dir("maven")) } }
group = rootProject.group
version = rootProject.version
kotlin {
    androidTarget { publishLibraryVariants("release"); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies { api(libs.kotlinx.coroutines.core) }
        androidMain.dependencies {
            implementation(libs.atomicx.core)
            implementation(libs.tencent.imsdk.plus)
            implementation(libs.kotlinx.coroutines.android)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        androidUnitTest.dependencies { implementation("org.robolectric:robolectric:4.16.1") }
    }
}
android {
    namespace = "io.github.gycrosskit.livesdk"
    compileSdk = 36
    defaultConfig { minSdk = 24; consumerProguardFiles("../consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
}
