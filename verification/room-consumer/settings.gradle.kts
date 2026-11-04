import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        maven { url = uri("https://maven.eazytec-cloud.com/nexus/repository/maven-public/"); content { includeVersionByRegex(".*", ".*", ".*-1\\.0\\.0") } }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://mirrors.tencent.com/nexus/repository/maven-public/") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // remoteOnly 必须直连精确公开坐标；候选仓库只在明确指定时使用。
        exclusiveContent {
            forRepository { maven { url = uri(if (providers.gradleProperty("remoteOnly").isPresent) "https://jitpack.io" else providers.gradleProperty("liveMavenRepo").orElse("https://jitpack.io").get()) } }
            filter { includeGroup("com.github.gycrosskit.live-sdk") }
        }
        exclusiveContent {
            forRepository { maven { url = uri("https://mirrors.tencent.com/nexus/repository/maven-tencent/") } }
            filter { includeGroup("com.tencent.kuikly-open") }
        }

        maven { url = uri("https://maven.eazytec-cloud.com/nexus/repository/maven-public/"); content { includeVersionByRegex(".*", ".*", ".*-1\\.0\\.0") } }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        maven { url = uri("https://mirrors.tencent.com/nexus/repository/maven-public/") }
        maven { url = uri("https://mirrors.tencent.com/repository/maven/thirdparty/") }
        maven { url = uri("https://mirrors.tencent.com/nexus/repository/maven-tencent/") }
    }
}

rootProject.name = "live-room-independent-consumer"
