plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    `maven-publish`
}
group = rootProject.group
version = rootProject.version
kotlin {
    androidTarget { publishLibraryVariants("release"); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    if (providers.gradleProperty("liveVerification").isPresent) {
        targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
            binaries.framework {
                baseName = "LiveKuikly"
                isStatic = true
                export(project(":live-core"))
            }
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":live-core"))
            api("com.tencent.kuikly-open:core:2.28.0-2.0.21-ohos")
        }
        androidMain.dependencies {
            api("com.tencent.kuikly-open:core-render-android:2.28.0-2.0.21-ohos")
            implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
publishing { repositories.maven { name = "staging"; url = uri(rootProject.layout.buildDirectory.dir("maven")) } }
android {
    namespace = "io.github.gycrosskit.livesdk.kuikly"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
}
