# 鸿蒙观看接线（未发布候选）

`@gycrosskit/live-native:0.3.0-rc.2` 是源码候选，尚无已验收的公开 HAR。与同一候选的 `live-core` / `live-kuikly` 配套使用；不能只安装 HAR 就绕过 Kotlin 公共会话。声明最低 SDK API 12，已取得固定 SHA 的真实 Hvigor/ArkTS 编译证据；当前修订以 CI 回执为准，真机音画尚未验收。

原生层只处理腾讯 SDK、ArkUI 视频节点和事件转换。`LiveBridgeAudienceSession` 复用 `AtomicAudienceSession`、`AtomicAudienceSessionCoordinator`、`LiveAudienceSnapshotStore`、`LiveLikeBatcher`；预览复用 `AtomicLivePreviewController`。Renderer 与 SDK UI 主线程之间异步传输，禁止阻塞互等。原生 Promise 队列额外保证即使公共会话超时，未结束的 SDK join/leave 也不会被下一场 SDK 操作越过。

## 依赖与注册

```bash
ohpm install @gycrosskit/live-native@0.3.0-rc.2
```


HAR 精确依赖腾讯 `atomicxcore 5.2.2`、`imsdk 9.1.7818`、`liteavsdk_professional 13.5.9128` 和 Kuikly Render `2.28.0`。腾讯依赖遵循其 Proprietary 许可，本仓库接线代码使用 Apache-2.0。Kotlin 侧使用 Kuikly `2.28.0-2.0.21-ohos`，宿主须统一版本。

在宿主原有外部 View 注册回调中追加以下注册，保留其他组件已有注册。组件自己的 `createArkUIView()` 创建 ArkUI 节点，宿主无需另接 Builder：

```ts
import { ViewsRegisterEntry } from '@kuikly-open/render';
import { GycLiveClient, GycLiveView } from '@gycrosskit/live-native';

ViewsRegisterEntry.registerViewCreator(GycLiveView.VIEW_NAME, () => new GycLiveView());
```

宿主在已同意隐私后，把服务端 UserSig、业务用户与真实 Ability Context 交给 `await GycLiveClient.login(sdkAppId, userId, userSig, context, nickname, avatarUrl)`。组件核对 IM 实际用户、IM/AtomicX 登录状态和 SDKAppId；异步昵称/头像不参与认证，不在客户端生成 UserSig。

在 Ability `onForeground()` / `onBackground()` 分别调用 `GycLiveClient.setForeground(true/false)`，默认 false，避免后台误播。ArkUI 组件另同步可见区域与移除事件。注销/切换账号先移除当前观看节点，再 `await GycLiveClient.logout()`；切换不同身份需先注销。借用的外部 IM 身份不会被本组件注销，外部账号必须由原 owner 清理；无法从 AtomicX 确认匹配 SDKAppId 的外部 IM 身份也拒绝借用。

Kotlin 继续使用既有 `io.github.gycrosskit.livesdk.kuikly.LiveVideo(liveId, preview, active, onEvent, onController)` 或 raw DSL。业务 UI 和房间 ID 由宿主提供，完整观看支持弹幕、点赞、观众列表/人数、公告、关播、被踢和禁言事件。

## 预览与限制

静音预览使用独立 `AtomicPlayer` 和 `livekit://liveid=<房间ID>`，不调用完整 `joinLive`。房间 ID 沿用腾讯接口原值，不自行改变编码。这一播放协议来自腾讯 [live-v2 官方播放器](https://github.com/Tencent-RTC/TUIKit_Android/blob/68fb94e5861bced0270ec881a084e78e48b8681d/live-v2/tuilivekit/src/main/java/com/trtc/uikit/livekit/player/PlayerViewImpl.kt#L142)，鸿蒙实机仍待验。完整观看先 `joinLive`，再绑定原生播放器；进房回执只证明 SDK 接受加入，不代表已看到首帧。

腾讯 `5.2.2` 的 ArkUI 挂载要求使用内部导出的 `AtomicLiveViewImpl`，上游没有兼容性承诺，因此锁定精确版本。播放状态、人数与禁言的 `@Observed` 标量没有独立公开订阅入口，播放期间每 250ms 检查变化；消息和观众列表使用 SDK 数据源监听，停止时全部撤销。

本轮不开放连麦、PK、开播、关注/粉丝、业务点赞 reporter、独立 IM 业务群监听和系统 PiP。PiP 返回 `accepted=false`，不会伪造实际状态。播放器错误发布 `playbackError`，不把普通断流当作真实关播。

SDK 离房失败会关闭账号门禁，避免声称 SDK 已空闲；自有账号须成功 logout 后重登，借用账号须交原 owner 恢复。未结算的 SDK Promise 会阻塞后续 SDK 操作，公共超时不代表 SDK 已停止。

## 最小真机验收

1. 测试环境登录观众账号，使用相同 SDKAppId / liveId，观看 Android/iOS 主播；核对实际画面和声音。
2. 三端互发文本及现有表情协议，核对原始内容、观众人数、禁言和点赞；SDK 成功与业务后台记账分别验收。
3. A→B→A 快速切房，退出发生在 join 在途、离房回执迟到及弱网时；旧消息、旧画面不能进入新房间。
4. 列表静音预览→完整观看→退出回列表，再切后台/前台、账号 A→注销→B；核对旧播放器释放及无后台声音。

本轮没有测试直播间与观众凭据，不运行云端用例，不使用生产账号或在仓库保存 UserSig。替身测试、SDK 声明类型检查、Kotlin 编译、HAR 编译、远程公开消费和真机验收分别记录在 [开发与验证](../../docs/开发与验证.md)。

远程 HAR 编译由源码 CI 的 `ohos-har` job 执行；使用华为 Linux x64 CLT 直链 `OHOS_CLT_URL` 和对应 SHA-256 `OHOS_CLT_SHA256`，SDK 必须匹配 API 22。本轮已核对官方 `6.0.2.670` 工具链，实际编译结果见开发与验证记录；产物归档不会发布到 ohpm 或创建 Release。
