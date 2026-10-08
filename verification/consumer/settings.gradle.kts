pluginManagement {
 repositories {
  maven { url = uri("https://maven.eazytec-cloud.com/nexus/repository/maven-public/"); content { includeVersionByRegex(".*", ".*", ".*-1\\.0\\.0") } }
  maven { url = uri("https://maven.aliyun.com/repository/google") }
  maven { url = uri("https://maven.aliyun.com/repository/public") }
  google(); mavenCentral(); gradlePluginPortal()
 }
}
dependencyResolutionManagement {
 repositories {
  maven { url = uri("https://maven.eazytec-cloud.com/nexus/repository/maven-public/"); content { includeVersionByRegex(".*", ".*", ".*-1\\.0\\.0") } }
  if (providers.gradleProperty("remoteOnly").isPresent) {
   exclusiveContent {
    forRepository { maven { url = uri("https://jitpack.io") } }
    filter { includeGroup("com.github.gycrosskit.live-sdk") }
   }
  } else {
   maven { url = uri("../../build/maven") }
  }
  maven { url = uri("https://maven.aliyun.com/repository/google") }
  maven { url = uri("https://maven.aliyun.com/repository/public") }
  google(); mavenCentral()
  maven { url = uri("https://mirrors.tencent.com/nexus/repository/maven-tencent/") }
  maven { url = uri("https://mirrors.tencent.com/repository/maven/thirdparty/") }
 }
}
rootProject.name = "live-independent-consumer"

// 本地候选源码消费，默认仍消费发布坐标；不得用此模式声称远程版本已发布。
if (providers.gradleProperty("verifyLocalSource").isPresent) {
    includeBuild("../..") {
        dependencySubstitution {
            substitute(module("com.github.gycrosskit.live-sdk:live-sdk")).using(project(":"))
            substitute(module("com.github.gycrosskit.live-sdk:live-core")).using(project(":live-core"))
            substitute(module("com.github.gycrosskit.live-sdk:live-kuikly")).using(project(":live-kuikly"))
        }
    }
}
