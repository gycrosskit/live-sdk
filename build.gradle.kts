import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
}

group = providers.environmentVariable("GROUP").orElse("com.github.gycrosskit.live-sdk").get()
version = providers.environmentVariable("VERSION").orElse("1.1.0").get()


allprojects {
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        // 这里同时注册依赖任务；不能放进禁止修改任务容器的 configureEach 回调。
        tasks.withType<KotlinNativeSimulatorTest>().all {
            val simulatorTest = this
            val owner = java.util.UUID.randomUUID().toString()
            val record = layout.buildDirectory.file("owned-ios-test/$name.device")
            val recordPath = record.get().asFile.absolutePath
            val script = rootProject.file("verification/owned-ios-test-simulator.sh")
            val prepare = tasks.register<Exec>("prepare${name.replaceFirstChar { it.uppercaseChar() }}Simulator") {
                onlyIf { simulatorTest.enabled }
                outputs.file(record)
                outputs.upToDateWhen { false }
                outputs.doNotCacheIf("Simulator ownership belongs to this invocation") { true }
                commandLine("bash", script, "create", recordPath, owner)
            }
            val cleanup = tasks.register<Exec>("cleanup${name.replaceFirstChar { it.uppercaseChar() }}Simulator") {
                onlyIf { simulatorTest.enabled }
                commandLine("bash", script, "cleanup", recordPath, owner)
                mustRunAfter(simulatorTest)
            }
            dependsOn(prepare)
            device.set(providers.fileContents(record).asText.map { it.lineSequence().first() })
            doFirst {
                check(record.get().asFile.readLines() == listOf(device.get(), owner)) {
                    "Native tests must use the Simulator created for this invocation; do not override --device"
                }
            }
            finalizedBy(cleanup)
            // 测试的其他依赖失败时也要回收已创建的设备。
            prepare.configure { finalizedBy(cleanup) }
        }
    }
    plugins.withId("maven-publish") {
        extensions.configure<org.gradle.api.publish.PublishingExtension> {
            publications.withType<org.gradle.api.publish.maven.MavenPublication>().configureEach {
                pom.licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
            }
        }
    }
}
