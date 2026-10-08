# GY CrossKit Live SDK

> 2026-10-08 本地未发布候选：Android/iOS 登录认证不再等待异步用户资料，保留实际 IM 账号、AtomicX/IM 状态、SDKAppId 与操作代次校验。当前 Maven `0.2.1-rc.12`、Git Pod `0.2.1-rc.7` 不包含此候选；真实宿主/设备登录退出仍待验证。合同与验证见[功能与平台差异](docs/功能与平台差异.md)。

2026-10-08 功能索引：live-core提供原生会话与快照，根模块提供CMP，live-kuikly提供raw与KuiklyCompose Android/iOS；OHOS直播排除，真实音画/PiP仍需业务验收。 详见[功能与平台差异](docs/功能与平台差异.md)，含固定基线、五入口矩阵、真实回归与未验收范围。当前发布组合：Maven 0.2.1-rc.12；未变GycLiveNative Git Pod继续0.2.1-rc.7，Kuikly Swift接线取本次Maven标签；OHOS仅core中立协议。各渠道消费与设备验收分别核对。

最终核对（2026-10-08）：本轮重跑Compose更新/句柄重建/迟回调；native会话为边界替身，真实音画/PiP未验。 逐项时点与边界见[验证范围](docs/功能与平台差异.md#sdk系统与真实验证范围)。

Maven `0.2.1-rc.12`新增 `io.github.gycrosskit.livesdk.kuikly.LiveVideo` Composable，此入口从该版本提供；已发布 Native DSL `io.github.gycrosskit.livesdk.LiveVideo(init)` 与raw `KuiklyLiveView` 保留兼容。`live-kuikly` 现在传递依赖 KuiklyCompose / Compose runtime，宿主统一 Kuikly 与 compiler 版本；没有引入第二套 JetBrains CMP UI。独立消费可用 `-PverifyKuiklyCompose -PverifyLocalSource` 验证本地源码；远程验收使用 `-PremoteOnly -PliveVersion=0.2.1-rc.12`。新的 `verifyNoCmpUi` 门禁允许 KuiklyCompose/runtime 并拒绝第二套 CMP UI；开启 API probe 时另确认 KuiklyCompose 确实存在，历史远程基线 raw API 仍可验证。

基于腾讯 AtomicX 的 Android/iOS 直播观看组件，支持列表静音预览、完整观看、原生视频画面和互动命令。CMP 与 Kuikly Native DSL 共用账号门禁、会话与状态；应用提供 SDKAppId、服务端 UserSig、业务账号、房间路由和操作 UI。

历史源码候选 **0.2.1-rc.11**（本次纳入0.2.1-rc.12）：iOS Kuikly 的 `release` 命令停止当前会话，hide/show 不恢复，下一份有效 room 可重新进入；native node 销毁仍永久 release。Android 同语义路径保持原样。源码回归和全部 16 个 Maven publication 归档校验通过，远程发布/消费和真实播放验收待继续执行；原生 Swift 未改，配套 Git Pod 仍为 rc.7。

历史 Maven **0.2.1-rc.10**：`live-core` 提供 62 项旧客户端兼容表情目录与 encode/decode，新增仅供中立协议/类型消费的 OHOS KLIB。root/CMP/Kuikly 原生播放仍仅支持 Android/iOS；Maven 三模块同版，未改原生 Swift，Git Pod 保持 **0.2.1-rc.7**。发布归档、JitPack 与三端远程验收分别见 [rc.10 Release](https://github.com/gycrosskit/live-sdk/releases/tag/0.2.1-rc.10)。

历史版本 **0.2.1-rc.9**：Android `GycLiveView` 在注册 Lifecycle observer 前准备快照订阅 scope，修复已 STARTED 宿主可播放视频但 loading、主播资料和人数停在默认值的竞态；保留 rc.8 的 FRAME 布局修复。布局与真实 attach 生命周期契约同时用于源码与远程 AAR 回归，发布/消费结果见 [rc.9 Release](https://github.com/gycrosskit/live-sdk/releases/tag/0.2.1-rc.9)。iOS 初始化无同源竞态，原生源码未变，配套 Git Pod 保持已验 **0.2.1-rc.7**；范围见[完整源码审查](docs/完整源码审查.md)。

上一轮 Maven 预发行 **0.2.1-rc.5** 已发布，修复 Renderer 停止预览的线程边界和退出后的 SDK 回调隔离：Renderer 使用 `stopActivePreviewAndAwait()`，同步 `stopActivePreview()` 仅供 Main 调用。Android 38 项、iOS Simulator 35 项测试与 iOS arm64 编译、完整归档、公开 Release 下载 SHA 和 JitPack 制品审计均通过，见 [rc.5 验收记录](docs/0.2.1-rc.5远程发布验收.md)。该轮原生 Swift 源码未变，配套已验 Git Pod `0.2.1-rc.3`；独立消费者与设备验收单独记录，历史结果见 [M19 记录](docs/M19验证记录.md)。


PiP 的 `accepted` 只表示请求受理；观看snapshot/event由两端真实平台或宿主生命周期回报驱动，关闭会话清理false。iOS实验接口准备成功不更新actual状态。

## 架构与调用流程

宿主先完成真实 SDK 登录，再更新组件账号门禁；iOS KMP 还需安装宿主桥。CMP 与 Kuikly Native DSL 复用 `live-core`，共享观看会话规则和唯一 StateFlow 快照，不各自维护一套 SDK 状态。

```mermaid
flowchart TB
    H["宿主<br/>登录 / UserSig / 房间 / UI"] --> G["账号门禁<br/>Android / iOS Runtime"]
    H --> C["CMP<br/>LivePreview / LiveCoreView"]
    H --> K["Kuikly<br/>LiveVideo / GycLiveView"]
    G --> C
    G --> K
    C --> P["live-core<br/>原生会话 / 预览控制器"]
    K --> P
    P --> A["Android AtomicX"]
    P --> I["宿主 IosLiveSdkBridge<br/>GycLiveNative"]
    P --> S["LiveAudienceSnapshotStore<br/>StateFlow"]
    S -.-> H
    H --> E["LiveEmojiProtocol<br/>commonMain / Android / iOS / OHOS"]
```

下面是 `AtomicAudienceSession` 的完整观看流程。会话排队、join 与 leave 分别有 15 / 20 / 8 秒期限；退出后仍须等离房结算再放行下一场，避免共享 Store 被旧房间回调误伤。

```mermaid
sequenceDiagram
    participant H as 原生 View
    participant S as Session
    participant G as Coordinator
    participant P as 预览
    participant X as AtomicX SDK
    H->>S: 创建完整观看会话
    S->>G: acquire(token, liveId)
    G-->>S: 轮到本会话：startJoin
    S->>P: Main 同步 stopActivePreview
    S->>X: joinLive
    X-->>S: joined 或 joinFailed
    S-->>H: joined 后 joinSucceeded
    H->>S: release()
    opt join 尚在途
        Note over S,X: 等 join 回调或超时<br/>随后清理
    end
    S->>X: leaveLive / 原生资源清理
    X-->>S: 离房成功或失败回调
    Note over S,G: 离房回调、异常或 8 秒超时<br/>结算令牌
    S->>G: release(token)<br/>取消会话 scope
    alt 还有排队会话
        G-->>S: 下一排队 Session 的 start 回调
    else 无等待会话
        G-->>P: 最新等待预览的 start 回调
    end
```

类型图聚焦 Android 完整观看链路（内部状态机和快照累加器同样被 iOS KMP 平台层复用）。列表预览另由 `AtomicLivePreviewController` 管理，只有 active、页面 STARTED 和平台账号门禁满足时播放；失活或释放取消等待预览并用 generation 拒绝迟回调。注销前宿主先关闭账号门禁、停止预览，再清理账号。

```mermaid
classDiagram
    class AndroidLiveAudienceSession {
        +view
        +snapshots
        +release()
    }
    class AtomicAudienceView {
        +snapshot
        +snapshots
        +release()
    }
    class AtomicAudienceSession {
        <<internal>>
        +joined()
        +joinFailed(code, message)
        +release()
    }
    class AtomicAudienceSessionGate {
        <<interface>>
        +acquire(token, liveId, onTimeout, start)
        +release(token)
    }
    class AtomicAudienceSessionCoordinator {
        <<internal>>
        +acquirePreview(token, start)
        +cancelPreview(token)
    }
    class LiveAudienceSnapshotStore {
        <<internal>>
        +snapshots
        +snapshot()
        +appendMessages(incoming)
    }
    AndroidLiveAudienceSession *-- AtomicAudienceView
    AtomicAudienceView *-- AtomicAudienceSession
    AtomicAudienceView *-- LiveAudienceSnapshotStore
    AtomicAudienceSession --> AtomicAudienceSessionGate
    AtomicAudienceSessionGate <|.. AtomicAudienceSessionCoordinator
```

源码入口：[Android 原生会话](live-core/src/androidMain/kotlin/io/github/gycrosskit/livesdk/AndroidLiveNativeSession.kt)、[AtomicAudienceView](live-core/src/androidMain/kotlin/io/github/gycrosskit/livesdk/AtomicAudienceView.kt)、[共用会话状态机](live-core/src/commonMain/kotlin/io/github/gycrosskit/livesdk/AtomicAudienceSession.kt)、[会话排队与预览门禁](live-core/src/commonMain/kotlin/io/github/gycrosskit/livesdk/AtomicAudienceSessionCoordinator.kt)、[预览生命周期](live-core/src/commonMain/kotlin/io/github/gycrosskit/livesdk/AtomicLivePreviewController.kt)、[唯一快照](live-core/src/commonMain/kotlin/io/github/gycrosskit/livesdk/LiveAudienceSnapshotStore.kt)、[Android 账号门禁](live-core/src/androidMain/kotlin/io/github/gycrosskit/livesdk/AndroidLiveSdkRuntime.kt)、[iOS 桥安装](live-core/src/iosMain/kotlin/io/github/gycrosskit/livesdk/IosLiveSdkRuntime.kt)。OHOS 仅有 core 中立协议 KLIB，没有 AtomicX 平台实现；Kuikly PiP 请求与状态同步同样复用平台会话，`accepted` 仅表示平台受理。

## 平台与模块

| 模块 | 平台 | 能力 |
| --- | --- | --- |
| `live-sdk` | Android、iOS | CMP `LivePreview` / `LiveCoreView` |
| `live-core` | Android、iOS；OHOS 中立协议 | Android/iOS SDK 账号门禁、观看会话、状态、互动和 iOS Bridge；OHOS 仅共用表情协议与中立类型 |
| `live-kuikly` | Android、iOS | Kuikly Native DSL、KuiklyCompose 与薄原生视频 View，无第二套 CMP UI |
| `GycLiveNative` | iOS | 独立 Swift CocoaPod，AtomicX 账号/视频/互动/IM/RoomEngine 系统 PiP；Git Pod `0.2.1-rc.7` 已发布并完成远程消费；`rc.6`/`rc.3` 历史验收保留 |

Android 最低 API 24。iOS Native 链接的已验证部署基线为 iOS 15，应用同时遵循所选腾讯 SDK 的部署要求。已验证工具链为 Kotlin `2.2.21-1.0.0`、AGP `8.10.1`、CMP `1.10.3`、Kuikly `2.28.0-2.0.21-ohos` / Render `2.28.0`。

**HarmonyOS 没有 AtomicX 直播/runtime 实现或 HAR**；rc.10仅为 `live-core` 添加 `ohosArm64` 协议变体，root CMP 与 Kuikly 原生播放模块仍只支持 Android/iOS。现有 AtomicX `liveId` 协议尚无等价接线。本版KuiklyCompose 入口可编译消费，真实 Renderer 音画仍需设备验收。

## 安装

在 `settings.gradle.kts` 添加依赖仓库：

```kotlin
dependencyResolutionManagement {
    repositories {
        // OHOS Kotlin/协程 fork（自 rc.10 提供）；普通依赖继续由下方仓库解析。
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/") {
            content { includeVersionByRegex(".*", ".*", ".*-1\\.0\\.0") }
        }
        exclusiveContent {
            forRepository {
                maven("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
            }
            filter { includeGroup("com.tencent.kuikly-open") }
        }
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

Kuikly group 固定从腾讯 Maven 读取 metadata 和实际产物，避免其他镜像先返回 metadata、随后 AAR 缺失时 Gradle 无法切源。此规则只匹配 `com.tencent.kuikly-open`，保留其他 SDK 的仓库选择；本轮响应与边界见 [rc.5 验收记录](docs/0.2.1-rc.5远程发布验收.md)。

以下远程0.2.1-rc.12坐标包含 `io.github.gycrosskit.livesdk.kuikly.LiveVideo` Composable。已发布同名Native DSL在 `io.github.gycrosskit.livesdk` 包。在 KMP 的 `commonMain.dependencies` 按 UI 引擎选择，同一应用全部模块固定同版本。当前 OHOS 宿主使用 Kotlin/Compose compiler `2.2.21-1.0.0`，`pluginManagement` 也配置上述受限 Eazytec 仓库；表情协议只需 `live-core`，root/Kuikly 播放模块不提供 OHOS 变体：

```kotlin
// CMP
implementation("com.github.gycrosskit.live-sdk:live-sdk:0.2.1-rc.12")
// Kuikly Native DSL
implementation("com.github.gycrosskit.live-sdk:live-kuikly:0.2.1-rc.12")
```

Android 传递依赖 `atomicx-core:4.3.3.29` 和 `imsdk-plus:9.1.7818`。iOS 应用保留 `IosLiveSdkBridge` 的薄协议映射。原生 Pod `GycLiveNative` 承接 SDK 实现，独立于 `Shared.framework`，厂商版本固定为 AtomicXCore `4.3.9`、RTCRoomEngine/Professional `4.3.9` 和 IM `9.1.7818`；Kuikly 另外加入当前 Maven 标签 `0.2.1-rc.12` 的 [GycLiveView.swift](https://github.com/gycrosskit/live-sdk/blob/0.2.1-rc.12/live-kuikly/ios/GycLiveView.swift) 与 `OpenKuiklyIOSRender`。KLIB 不能代替原厂 SDK 或 Swift 接线，详见 [接入指南](docs/接入指南.md)。

## iOS 原生接入

`GycLiveNative` 通过不可变 Git 标签安装。`0.2.1-rc.3` 的 JitPack 全变体下载、远程 Gradle 消费与 Git Pod UIKit 最终链接已通过，见 [M19 记录](docs/M19验证记录.md) 和[同版本预发布](https://github.com/gycrosskit/live-sdk/releases/tag/0.2.1-rc.3)。本仓库没有 Swift Package 或 CocoaPods Specs 发布。以下为已发布并完成真实 Git Pod/App 链接消费的 `0.2.1-rc.7` 安装方式：

```ruby
pod 'GycLiveNative', :git => 'https://github.com/gycrosskit/live-sdk.git', :tag => '0.2.1-rc.7'
```

纯 UIKit 应用可 `import GycLiveNative` 后复用 `GycLiveClient.shared`。KMP 应用继续安装自己的 `IosLiveSdkBridge`，把 Shared 回调映射为组件的 Swift 协议。账号准备与 UserSig、观看排队与超时、业务 IM 解析、前台可拖动小窗和 UI 仍由宿主负责。完整 API、释放与系统 PiP 边界见 [接入指南](docs/接入指南.md#ios-原生-cocoapod)。当前固定组合为 Maven `0.2.1-rc.12` + Native Pod `0.2.1-rc.7`；各渠道结果见完整源码审查。

## 快速使用

先完成真实 SDK 登录，再设置账号门禁：Android 调用 `AndroidLiveSdkRuntime.updateSessionReady(true)`；iOS 先 `IosLiveSdkRuntime.install(bridge)`，登录成功后调用 `updateSessionReady(true)`。组件不生成 UserSig，也不主动替应用登录。

Renderer/后台协程在后续登录、重置或完整进房前，调用平台 Runtime 的 `stopActivePreviewAndAwait()`，挂起等待 Main 停止 SDK 预览并移除 View。原有同步 `stopActivePreview()` 保留 Main 即时语义，后台调用会立即拒绝；不要用阻塞等待连接 Renderer 与 Main。具体账号和回调合同见 [线程与停止顺序](docs/接入指南.md#线程与停止顺序)。

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

完整观看使用 `LiveCoreView`；已发布Kuikly使用 `io.github.gycrosskit.livesdk.LiveVideo(init)` Native DSL并注册原生 `GycLiveView`。Composable `io.github.gycrosskit.livesdk.kuikly.LiveVideo(liveId, ...)`自0.2.1-rc.12提供。预览只在页面具备 LifecycleOwner、处于 STARTED 且 `active=true` 时播放。完整接线和互动回调见 [接入指南](docs/接入指南.md)。

## 兼容表情收发（rc.10）

`live-core` 的 `LiveEmojiProtocol` 共用 62 项既有客户端兼容映射，CMP 和 Kuikly 均通过传递依赖访问；这份目录不代表厂商官方 token 标准全集。目录顺序保持不变，图片、品牌资源、尺寸、选择与删除等编辑行为由宿主负责。

```kotlin
import io.github.gycrosskit.livesdk.LiveEmojiProtocol

// 三端纯协议宿主：implementation("com.github.gycrosskit.live-sdk:live-core:0.2.1-rc.12")
val catalog = LiveEmojiProtocol.items // 每项提供 display 展示字符和 token 收发原文
val outgoing = LiveEmojiProtocol.encode("你好😮‍💨❤️") // 你好[TUIEmoji_Sigh][TUIEmoji_Heart]
val incoming = LiveEmojiProtocol.decode("[TUIEmoji_Heart][TUIEmoji_Unknown]") // ❤️[TUIEmoji_Unknown]
```

发送前编码、展示前解码均由宿主调用；组件不会自动修改 IM 消息。组合字符优先最长匹配，已编码 token、未知 token、未支持表情与普通文本保持原文。此 API 位于 `commonMain`，`live-core` 的 Android/iOS/OHOS 变体共用同一映射；OHOS 宿主直接依赖 `live-core`，不会获取 AtomicX 会话、播放或 SDK 登录实现。当前 Maven 的 root/core/Kuikly 同为 `0.2.1-rc.12`，原生 Swift 未改，Git Pod 保持 `0.2.1-rc.7`；发布与远程消费结果须分别核验。

## 生命周期与能力边界

- 业务动作应等待 `joinSucceeded`，不能把请求提交或画面创建当成进房成功。
- 完整观看与列表预览互斥；释放后等待 SDK 离房回调或既有超时，才放行下一会话。旧回调不能更新新房间。
- 注销或撤销播放资格前先关闭账号门禁、停止预览，再执行应用的账号清理。CMP、Kuikly 不应另建第二套 SDK 账号状态。
- 组件提供视频与互动命令，不提供完整直播间 UI、礼物、上麦、开播、服务端协议；Kuikly PiP 命令复用 core，actual 状态由真实系统或宿主回报。

## 文档与反馈

- [共享腾讯身份与中立 IM 源](docs/共享腾讯身份.md)
- [SDK 账号、CMP/Kuikly 与 iOS 接入](docs/接入指南.md)
- [源码开发与验证](docs/开发与验证.md)、[验证记录](docs/验证记录.md)
- [HarmonyOS SDK 调研与限制](docs/腾讯鸿蒙直播SDK调研.md)
- [版本发布](https://github.com/gycrosskit/live-sdk/releases)、[问题反馈](https://github.com/gycrosskit/live-sdk/issues)

由 GY CrossKit 维护。反馈请提供组件/原厂 SDK/系统版本、脱敏事件顺序与最小复现，不提交真实 UserSig 或账号凭据。

## 许可证

[Apache-2.0](LICENSE)。腾讯 SDK 与 Kuikly Render 遵循各自原厂许可，组件不重新分发 proprietary SDK。

## 0.2.1-rc.4 历史候选

观众快照只由现有 `StateFlow` 保存，`snapshot()` 与订阅者读取同一份状态，回调通过 `copy` 保留其他字段。
弹幕队列、去重 key、有界容量与合成序号继续独立维护；平台回调原有主线程串行语义保持。

本轮 core Android 35 项、Simulator 32 项测试和 core/CMP/Kuikly Android、iOS arm64/Simulator 编译通过；未执行真实直播业务。

| rc.4 渠道 | 配套版本 |
| --- | --- |
| Maven / Git Pod | `0.2.1-rc.4` / `0.2.1-rc.3` |

Android AtomicX 4.3.3.29 + IM 9.1.7818；iOS AtomicX/RoomEngine 4.3.9 + IM 9.1.7818；Kuikly Render 2.28.0。候选已完成发布与新版本远程消费；设备行为不由编译/链接推断。

## 0.2.1-rc.4 发布与远程验收

Fresh macOS staging 与归档解包复验均通过，全部 15 个 publication 的声明文件四类哈希、四类 sidecar、Apache-2.0 POM 及同名 available-at 目标身份均已校验。Maven 归档 SHA-256：`7ab65c2249f8284f57ae59bba03b40e600dcf76c735033cf34e4377a665a67b3`。

Maven `0.2.1-rc.4`；未变 GycLiveNative Git Pod 保留 `0.2.1-rc.3`。

不可变标签与 prerelease 已发布，所有 Release 附件重下载 SHA 与清单匹配。JitPack 新版本最终 ok/isTag/public 且 commit 匹配 tag，全部 15 module、18 个文件引用、15 个 available-at 的 HTTP/四类声明 hash/身份验证通过。新版真实远程 consumer 已通过；设备与业务 SDK 动作未验。

精确 JitPack rc.4 新目录消费者：Kuikly 43 tasks / 43s，APK/D8、verifyNoCompose、verifySingleSdk（core/AtomicX 唯一依赖）、iOS 三架构编译和 device/simulator arm64 static Framework；CMP 19 tasks / 19s，Android 与 iOS 三架构编译。未变 Git Pod rc.3 沿用既有真实 UIKit 链接证据，本轮不重复发布或编译旧 Pod。

实际日志与 JSON 账单位于 `build/remote-library-review/`。真实设备、业务账号登录/聊天/直播/PiP、权限 UI、真实 Bug/通知发送未执行。

## 0.2.1-rc.6 本轮测试与远程验收

2026-10-05：本轮自有源码和公开 API 审查、关键回归与受影响平台编译通过；真实 JitPack `0.2.1-rc.6` 的最终标签提交、15 个 publications 的 POM/Module、所有变体文件大小与四种声明哈希、内部精确版本及 available-at 均通过。Release Maven 归档重新下载 SHA-256 为 `6dc24dfab7b3309a58591b14c1ed3cb97605cbaf048ca3236c0410eb69ac35bb`。公开 MD5/SHA-1 sidecar 通过；SHA-256/SHA-512 sidecar 的 HTTP 404 记录为渠道缺失。

干净消费工程使用固定远程版本，没有本地 Maven、includeBuild 或其他组件源码替代；通过现有入口的 Android/iOS 编译和相应最终链接。 Kuikly 与 CMP 分别验证。 新 Git Pod 从远程标签安装，实际编译 Swift 与标签逐字节匹配，纯 UIKit iphoneos arm64 App 链接通过。

完整回归范围、精简原则、注释契约与仍需设备/业务验收的边界见 [14 个功能组件测试与 API 审查](https://github.com/gycrosskit/.github/blob/main/docs/组件测试与API审查.md)。源码测试与远程消费不代替真机和厂商业务验收。

## 自动回归

[Source regression](.github/workflows/regression.yml) 的当前工作树候选按事件分阶段：PR 先判断变更范围，仅源码变更运行已有 Android/Native 测试与编译；纯文档 PR 和 `main` push 只运行轻量脚本/配置检查。手动运行不填版本时执行源码回归，未知路径保守按源码处理。候选尚未合入，线上生效与耗时以实际 Actions 运行为准。

[Release validation](.github/workflows/release-validation.yml) 在 Maven Release 发布或手动填写精确已发布版本时，`verify-public` 统一校验一次冻结归档、精确 tag/commit、完整 publication 清单和公开文件；通过后 Android/Native 独立消费者从 JitPack 解析该版本。PR 不再反复消费旧基线；不使用 `mavenLocal`、本库源码或归档替换远程依赖。此流程不发布二进制。

GitHub-hosted runner 的实际结果以 Actions 为准；没有 DevEco/ohpm runner，因此 HAR 构建、ohpm Registry 安装、完整原生 SDK 集成和真机业务验收仍按既有验证文档执行，不能由这些 job 的成功代算。

远程 Android 消费分别以 Kuikly-only 和 CMP-only 配置编译，并检查两者运行时依赖隔离。原生 Kuikly iOS 消费编译 API；其真实 Render Framework 最终链接仍需既有脚本的 SDK 参数。CMP-only iOS Simulator 消费单独链接 Framework。

阶段、缓存、有限网络重试、失败记录与证据边界见[共用 CI 规则](https://github.com/gycrosskit/.github/blob/main/docs/持续集成门禁.md)；本库实际平台命令以 workflow 为准。源码通过、远程消费、HAR/ohpm 与设备验收分别记录。

指定版本消费者按版本合同启用新表情 API 与 OHOS core 探针，并对公开 Kuikly Android AAR 执行 FRAME 布局与快照生命周期契约；OHOS core 编译不代表 OHOS 直播支持。

公网核验同步组织 `templates/check-public-maven.py`：使用冻结归档给出的完整 publications 清单，核对 JitPack tag/commit、每个公开 POM/Module、全部声明变体字节大小和四类哈希、内部精确版本及 `available-at`；MD5/SHA-1 sidecar 必须匹配。SHA-256/SHA-512 sidecar 的 HTTP 404 单独输出为渠道缺失，不计为校验通过。
