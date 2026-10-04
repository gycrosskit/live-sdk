# GY CrossKit Live SDK

基于腾讯 AtomicX 的 Android/iOS 直播观看组件，支持列表静音预览、完整观看、原生视频画面和互动命令。CMP 与 Kuikly Native DSL 共用账号门禁、会话与状态；应用提供 SDKAppId、服务端 UserSig、业务账号、房间路由和操作 UI。

当前 Maven 候选 **0.2.1-rc.4** 精简观众快照为单一 StateFlow，保留弹幕队列、去重、序号和主线程语义。原生 Swift 源码未变，继续使用已验 Git Pod `0.2.1-rc.3`；新 Maven 完整归档与远程消费正在执行。`0.2.1-rc.2` 真实远程检查发现空资源 ZIP 引用 404 和 source 变体哈希失配，请使用后继候选，详情见 [M19 记录](docs/M19验证记录.md)。

## 平台与模块

| 模块 | 平台 | 能力 |
| --- | --- | --- |
| `live-sdk` | Android、iOS | CMP `LivePreview` / `LiveCoreView` |
| `live-core` | Android、iOS | SDK 账号门禁、观看会话、状态、互动和 iOS Bridge |
| `live-kuikly` | Android、iOS | Kuikly Native DSL 与薄原生视频 View，无 CMP UI/runtime |
| `GycLiveNative` | iOS | 独立 Swift CocoaPod，AtomicX 账号/视频/互动/IM/RoomEngine 系统 PiP；Git Pod 候选 `0.2.1-rc.3` |

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
implementation("com.github.gycrosskit.live-sdk:live-sdk:0.2.1-rc.4")
// Kuikly Native DSL
implementation("com.github.gycrosskit.live-sdk:live-kuikly:0.2.1-rc.4")
```

Android 传递依赖 `atomicx-core:4.3.3.29` 和 `imsdk-plus:9.1.7818`。iOS 应用保留 `IosLiveSdkBridge` 的薄协议映射。原生 Pod `GycLiveNative` 承接 SDK 实现，独立于 `Shared.framework`，厂商版本固定为 AtomicXCore `4.3.9`、RTCRoomEngine/Professional `4.3.9` 和 IM `9.1.7818`；Kuikly 另外加入同标签 [GycLiveView.swift](https://github.com/gycrosskit/live-sdk/blob/0.2.1-rc.3/live-kuikly/ios/GycLiveView.swift) 与 `OpenKuiklyIOSRender`。KLIB 不能代替原厂 SDK 或 Swift 接线，详见 [接入指南](docs/接入指南.md)。

## iOS 原生接入

`GycLiveNative` 通过不可变 Git 标签安装。候选 `0.2.1-rc.3` 的 JitPack 全变体下载、远程 Gradle 消费与 Git Pod UIKit 最终链接已通过，见 [M19 记录](docs/M19验证记录.md) 和[同版本预发布](https://github.com/gycrosskit/live-sdk/releases/tag/0.2.1-rc.3)。本仓库没有 Swift Package 或 CocoaPods Specs 发布：

```ruby
pod 'GycLiveNative', :git => 'https://github.com/gycrosskit/live-sdk.git', :tag => '0.2.1-rc.3'
```

纯 UIKit 应用可 `import GycLiveNative` 后复用 `GycLiveClient.shared`。KMP 应用继续安装自己的 `IosLiveSdkBridge`，把 Shared 回调映射为组件的 Swift 协议。账号准备与 UserSig、观看排队与超时、业务 IM 解析、前台可拖动小窗和 UI 仍由宿主负责。完整 API、释放与系统 PiP 边界见 [接入指南](docs/接入指南.md#ios-原生-cocoapod)。当前兼容组合为 Maven `0.2.1-rc.4` + Native Pod `0.2.1-rc.3`，两种发布产物分别验证并记录。

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

- [共享腾讯身份与中立 IM 源](docs/共享腾讯身份.md)
- [SDK 账号、CMP/Kuikly 与 iOS 接入](docs/接入指南.md)
- [源码开发与验证](docs/开发与验证.md)、[验证记录](docs/验证记录.md)
- [HarmonyOS SDK 调研与限制](docs/腾讯鸿蒙直播SDK调研.md)
- [版本发布](https://github.com/gycrosskit/live-sdk/releases)、[问题反馈](https://github.com/gycrosskit/live-sdk/issues)

由 GY CrossKit 维护。反馈请提供组件/原厂 SDK/系统版本、脱敏事件顺序与最小复现，不提交真实 UserSig 或账号凭据。

## 许可证

[Apache-2.0](LICENSE)。腾讯 SDK 与 Kuikly Render 遵循各自原厂许可，组件不重新分发 proprietary SDK。

## 0.2.1-rc.4 发布候选

观众快照只由现有 `StateFlow` 保存，`snapshot()` 与订阅者读取同一份状态，回调通过 `copy` 保留其他字段。
弹幕队列、去重 key、有界容量与合成序号继续独立维护；平台回调原有主线程串行语义保持。

本轮 core Android 35 项、Simulator 32 项测试和 core/CMP/Kuikly Android、iOS arm64/Simulator 编译通过；未执行真实直播业务。

| 当前候选渠道 | 配套版本 |
| --- | --- |
| Maven / Git Pod | `0.2.1-rc.4` / `0.2.1-rc.3` |

Android AtomicX 4.3.3.29 + IM 9.1.7818；iOS AtomicX/RoomEngine 4.3.9 + IM 9.1.7818；Kuikly Render 2.28.0。候选尚待新版本远程验收，设备行为不由编译/链接推断。
