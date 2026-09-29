# Compose Multiplatform 直播 SDK

Android/iOS 直播观看组件，封装腾讯 AtomicX 的账号操作、列表预览、观看会话、原生视频 View 与互动命令。业务账号、UserSig、SDK AppId、房间路由和页面 UI 由接入方管理。

## 平台与接入

| 平台 | 组件提供 | 宿主仍需提供 |
| --- | --- | --- |
| Android | KMP API、AtomicX/IM 适配、Compose 原生 View | UserSig 与账号准备、业务页面 |
| iOS | KMP API、UIKitView 包装、`IosLiveSdkBridge` 契约 | 实现 `IosLiveSdkBridge` 的 Swift 代码及 AtomicXCore/RoomEngine/IM Pod |

本仓库发布供 KMP 工程使用的 Maven 依赖；iOS 产物是 KLIB，不是独立 Swift Package。已有 iOS 宿主可继续编译自己的 Swift 桥，并通过 `IosLiveSdkRuntime.install(bridge)` 注入。

```kotlin
// settings.gradle.kts
maven { url = uri("https://jitpack.io") }

// KMP 模块的 commonMain.dependencies
api("com.github.gycrosskit.live-sdk:live-sdk:0.1.1")
```

Android 接入方通过 `AtomicXSession` 准备 SDK 账号；两端在账号准备成功后分别调用 `AndroidLiveSdkRuntime.updateSessionReady(true)`、`IosLiveSdkRuntime.updateSessionReady(true)`，注销前先置为 `false` 并停止预览。`LivePreview` 与 `LiveCoreView` 只负责底层画面，应用负责完整的直播间 UI 和账号清理顺序。

## Kuikly 边界

当前 API 使用 Compose Multiplatform，不支持直接作为 Kuikly 控件使用。Android/iOS 将来接入 Kuikly 时，应复用同一原生 SDK 账号与观看会话，再为 Kuikly 增加原生视频 View 接线；不要创建第二套 SDK 登录。鸿蒙需要单独确认可用的原生直播 SDK，当前产物没有 OHOS target。

## 本地验证

使用 JDK 17、Android SDK 36 和 Xcode：

```bash
bash gradlew testDebugUnitTest compileKotlinIosSimulatorArm64
```

JitPack 按 Git 标签使用 JDK 17 执行 `publishToMavenLocal`，发布 Android AAR 与 iOS KLIB。请使用上面的 KMP 模块坐标，而非仓库聚合坐标。

## 许可证

Apache-2.0，见 [LICENSE](LICENSE)。腾讯 SDK 由接入方通过原厂依赖单独取得和使用。
