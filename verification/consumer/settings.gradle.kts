pluginManagement {
 repositories {
  maven { url = uri("https://maven.aliyun.com/repository/google") }
  maven { url = uri("https://maven.aliyun.com/repository/public") }
  google(); mavenCentral(); gradlePluginPortal()
 }
}
dependencyResolutionManagement {
 repositories {
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
