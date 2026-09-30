# 直播观看组件：CMP / Kuikly Native DSL

Android/iOS 观看组件，复用腾讯 AtomicX 账号、列表静音预览、观看会话、原生视频 View 与互动命令。宿主提供 SDKAppId、服务端 UserSig、业务账号、页面 UI、房间路由、点赞业务上报及业务策略。

本轮预发布版本为 `0.2.0-rc.1`；生产接入前仍需真实账号与设备验收。原 CMP `0.1.1` 坐标继续可用。HarmonyOS 尚不能等价支持现有 AtomicX `liveId` 协议，详见 [官方 SDK 调研](docs/腾讯鸿蒙直播SDK调研.md)。当前没有 OHOS target/HAR，也不返回假的进房或播放成功。

鸿蒙直播实现已暂停：用户转述腾讯人员计划节后发布 SDK，当前等待正式版本，再核对 API 和三端互通；TRTC 替代方案仅保留为调研记录。

## 模块与平台

| 模块 | 内容 | UI 依赖 |
| --- | --- | --- |
| 根 `live-sdk` | 原 CMP `LivePreview` / `LiveCoreView` / `LivePlaybackState` | CMP runtime/ui |
| `live-core` | 原有 SDK 账号、会话队列、预览状态机、StateFlow 快照、互动、原生视频 View 与 iOS Bridge | 无 Compose / Kuikly |
| `live-kuikly` | `LiveVideo` / `KuiklyLiveView`、Android `GycLiveView`、iOS Controller 与 Swift 原生 View | Kuikly core/render，**Native DSL** |

Android SDK 仍依赖 `io.trtc.uikit:atomicx-core:4.3.3.29` 与 `com.tencent.imsdk:imsdk-plus:9.1.7818`。iOS 继续由宿主实现 `IosLiveSdkBridge`，并链接原厂 AtomicXCore/RoomEngine/IM Pod；本组件不会生成 UserSig、复制业务 Repository 或再次实现登录。

Kuikly Compose DSL 不在本轮验证范围。Kuikly 视频层与 CMP 可在同一应用内共用 `live-core`；原生 SDK 账号、全局观看会话队列和快照规则只有一份。完整观看与预览互斥；移除控件和 `release()` 会清理观看会话，等待 SDK 离房回调或既有超时后放行下一会话。

## 依赖坐标

```kotlin
// settings.gradle.kts
maven { url = uri("https://jitpack.io") }

// CMP / Kuikly 使用同一预发布版本，避免混用 core。
api("com.github.gycrosskit.live-sdk:live-sdk:0.2.0-rc.1")
api("com.github.gycrosskit.live-sdk:live-kuikly:0.2.0-rc.1")
```

本地独立消费工程从 `build/maven` 读取候选，使用 `com.github.gycrosskit.live-sdk` group。iOS Maven 产物是 KLIB，不是独立 Swift Package。导出 Swift Framework 时须 `export(live-core)`，供现有 Swift `IosLiveSdkBridge` 实现使用；Kuikly 工程还需导出 `live-kuikly`。

## 账号准备

Android 继续通过 `AtomicXSession` 使用宿主提供的 SDK 账号和 UserSig，成功后调用 `AndroidLiveSdkRuntime.updateSessionReady(true)`。iOS 启动时继续 `IosLiveSdkRuntime.install(existingBridge)`，账号准备成功后 `updateSessionReady(true)`。注销或撤销播放资格前先设为 `false` 并停止预览，然后执行既有账号清理流程。

`sessionReadyFlow` 是只读门禁；视频节点读取它，不自行登录。Native 与 CMP 读取相同 `LiveAudienceContentSnapshot`，平台 Store 只进入 `live-core`。

## Kuikly 接线

```kotlin
LiveVideo {
    attr {
        size(320f, 180f)
        room(liveId, preview = true, active = visible)
    }
    event {
        liveEvent { event ->
            // type: previewState、joinStarted、joinSucceeded、joinFailed、
            // liveUnavailable、kickedOut、liveEnded、messageDisabled、snapshot
        }
    }
}
```

完整观看使用 `preview = false`。业务只能在 `joinSucceeded` 后继续执行需要进房成功的动作；`snapshot` 使用原有快照字段，成员事件 `kind` 保留语义，最终文案由宿主提供。`sendBarrage`、`like`、`toggleFollow`、`refreshAudience`、`release` 通过 Kuikly View 方法转发现有 SDK 命令。配置采用单个 `room` JSON，避免属性顺序导致错误模式的 SDK 会话；旧会话迟到回调不会更新新房间。

Android 在 Kuikly delegate 的 `registerExternalRenderView` 注册：

```kotlin
kuiklyRenderExport.renderViewExport(KuiklyLiveView.VIEW_NAME, { context ->
    GycLiveView(context).also { it.likeReporter = existingBusinessLikeReporter }
})
```

预览需要宿主的 `ViewTreeLifecycleOwner`，页面至少 STARTED 且 `active=true` 才播放；没有 LifecycleOwner 时保持封面。视频节点不提供操作层，宿主处理触摸、可访问性、语言、主题与业务导航。

iOS 将 [GycLiveView.swift](live-kuikly/ios/GycLiveView.swift) 加入宿主 target，Framework 模块名为 `LiveKuikly`，链接 `OpenKuiklyIOSRender` 2.28.0。类通过 `@objc(GycLiveView)` 供 Kuikly 动态发现；现有 `IosLiveSdkBridge` 负责真实腾讯 SDK。宿主可在创建节点后设置 `likeReporter`。Swift View 响应页面挂载和 UIApplication 前后台事件，停止列表预览；销毁时幂等释放。静态 KMP Framework 的 native C 符号由最终宿主的真实 Kuikly Render Framework 提供。

PiP 仍通过原 CMP/宿主既有原生能力使用；本轮 Kuikly View 未新增 PiP 指令。完整业务页面、礼物、上麦、开播和服务器协议不由本组件实现。

## 验证与归档

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
bash scripts/verify.sh
# 指定真实原厂 Render Framework 后做 Swift 类型检查和 iphoneos 最终链接
LIVE_KUIKLY_IOS_FRAMEWORK_DIR=/path/to/parent/of/OpenKuiklyIOSRender.framework bash scripts/verify-ios-native.sh
```

脚本执行受影响 Android/iOS 编译、状态机/协议测试、staging Maven 消费、Android APK/D8、依赖无 Compose 检查与同时消费 CMP/Kuikly 的单 SDK 检查。[验证记录](docs/验证记录.md)区分源码、编译、产物消费、最终链接和设备验收。

`archive-prebuilt.sh` 为 macOS 生成 Maven 归档，腾讯 SDK 仍使用原厂依赖；JitPack 在发布后校验 `release-checksums.txt` 中 immutable tag 的归档哈希，不在 Linux 临时编译 UIKit KLIB。预发布归档的校验值记录在 `release-checksums.txt`；远程消费验证使用 `-PremoteOnly -PliveVersion=0.2.0-rc.1`，该模式仅从 JitPack 读取本组件，排除 staging 与 mavenLocal。

## 许可证

Apache-2.0，见 [LICENSE](LICENSE)。腾讯 SDK 及 Kuikly Render 使用各自原厂许可证；调研下载的 proprietary HAR 只用于 API 核对，不随组件重新分发。
