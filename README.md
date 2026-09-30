# GY CrossKit Live SDK

基于腾讯 AtomicX 的 Android/iOS 直播观看组件，支持列表静音预览、完整观看、原生视频画面和互动命令。CMP 与 Kuikly Native DSL 共用账号门禁、会话与状态；应用提供 SDKAppId、服务端 UserSig、业务账号、房间路由和操作 UI。

当前版本 **0.2.0-rc.2（预发布）**，见 [Release](https://github.com/gycrosskit/live-sdk/releases/tag/0.2.0-rc.2)。远程 Maven 与原生源码消费已有验证，生产账号/设备业务尚需验收。

## 平台与模块

| 模块 | 平台 | 能力 |
| --- | --- | --- |
| `live-sdk` | Android、iOS | CMP `LivePreview` / `LiveCoreView` |
| `live-core` | Android、iOS | SDK 账号门禁、观看会话、状态、互动和 iOS Bridge |
| `live-kuikly` | Android、iOS | Kuikly Native DSL 与薄原生视频 View，无 CMP UI/runtime |

Android 最低 API 24。iOS Native 链接的已验证部署基线为 iOS 15，应用同时遵循所选腾讯 SDK 的部署要求。已验证工具链为 Kotlin `2.2.21`、AGP `8.10.1`、CMP `1.10.3`、Kuikly `2.28.0-2.0.21-ohos` / Render `2.28.0`。

**HarmonyOS 暂无发布实现、OHOS target 或 HAR**；现有 AtomicX `liveId` 协议尚无等价接线。Kuikly Compose DSL 也不在当前验证范围。

## 安装

在 `settings.gradle.kts` 添加依赖仓库：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroup("com.github.gycrosskit.live-sdk") }
        }
        maven("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
        maven("https://mirrors.tencent.com/repository/maven/thirdparty/")
    }
}
```

在 KMP 的 `commonMain.dependencies` 按 UI 引擎选择，同一应用全部模块固定同版本：

```kotlin
// CMP
implementation("com.github.gycrosskit.live-sdk:live-sdk:0.2.0-rc.2")
// Kuikly Native DSL
implementation("com.github.gycrosskit.live-sdk:live-kuikly:0.2.0-rc.2")
```

Android 传递依赖 `atomicx-core:4.3.3.29` 和 `imsdk-plus:9.1.7818`。iOS 需要应用实现 `IosLiveSdkBridge` 并链接真实腾讯 SDK；Kuikly 另外加入同标签 [GycLiveView.swift](https://github.com/gycrosskit/live-sdk/blob/0.2.0-rc.2/live-kuikly/ios/GycLiveView.swift) 与 `OpenKuiklyIOSRender`。KLIB 不能代替原厂 SDK 或 Swift 接线，详见 [接入指南](docs/接入指南.md)。

## 快速使用

先完成真实 SDK 登录，再设置账号门禁：Android 调用 `AndroidLiveSdkRuntime.updateSessionReady(true)`；iOS 先 `IosLiveSdkRuntime.install(bridge)`，登录成功后调用 `updateSessionReady(true)`。组件不生成 UserSig，也不主动替应用登录。

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import io.github.gycrosskit.livesdk.LivePreview

@Composable
fun PreviewItem(liveId: String, visible: Boolean) {
    LivePreview(
        liveId = liveId,
        active = visible,
        modifier = Modifier.size(320.dp, 180.dp),
    )
}
```

完整观看使用 `LiveCoreView`；Kuikly 使用 `LiveVideo` 并注册原生 `GycLiveView`。预览只在页面具备 LifecycleOwner、处于 STARTED 且 `active=true` 时播放。完整接线和互动回调见 [接入指南](docs/接入指南.md)。

## 生命周期与能力边界

- 业务动作应等待 `joinSucceeded`，不能把请求提交或画面创建当成进房成功。
- 完整观看与列表预览互斥；释放后等待 SDK 离房回调或既有超时，才放行下一会话。旧回调不能更新新房间。
- 注销或撤销播放资格前先关闭账号门禁、停止预览，再执行应用的账号清理。CMP、Kuikly 不应另建第二套 SDK 账号状态。
- 组件提供视频与互动命令，不提供完整直播间 UI、礼物、上麦、开播、服务端协议；Kuikly 当前未新增 PiP 指令。

## 文档与反馈

- [SDK 账号、CMP/Kuikly 与 iOS 接入](docs/接入指南.md)
- [源码开发与验证](docs/开发与验证.md)、[验证记录](docs/验证记录.md)
- [HarmonyOS SDK 调研与限制](docs/腾讯鸿蒙直播SDK调研.md)
- [版本发布](https://github.com/gycrosskit/live-sdk/releases)、[问题反馈](https://github.com/gycrosskit/live-sdk/issues)

由 GY CrossKit 维护。反馈请提供组件/原厂 SDK/系统版本、脱敏事件顺序与最小复现，不提交真实 UserSig 或账号凭据。

## 许可证

[Apache-2.0](LICENSE)。腾讯 SDK 与 Kuikly Render 遵循各自原厂许可，组件不重新分发 proprietary SDK。
