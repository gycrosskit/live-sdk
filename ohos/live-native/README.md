# 鸿蒙观看接线（RC）

> 当前源码候选统一为 **1.0.1**：Maven（core/Kuikly）、iOS Pod 与 OHPM 使用同版；尚未发布，示例坐标须待发布后远程消费。本地验证使用显式源码/本地 HAR，Kuikly 基线为 2.29.1，真机与新版本远程消费另验。

当前候选 `@gycrosskit/live-native@1.0.1` 配套 JitPack `live-core` / `live-kuikly:1.0.1`，包含主 TRTC 观看绑定、成员事件、同会话 PiP 与比例修复。Registry 查询、精确安装及编译结果以本版本 Release 收据为准，不能沿用旧 rc.3 的发布结果。源码已完成宿主本地编译与横向房间真机分项验收；旧 Registry rc.3 不包含这些修复。

原生层只处理腾讯 SDK、ArkUI 视频节点和事件转换。`LiveBridgeAudienceSession` 复用 `AtomicAudienceSession`、`AtomicAudienceSessionCoordinator`、`LiveAudienceSnapshotStore`、`LiveLikeBatcher`；预览复用 `AtomicLivePreviewController`。Renderer 与 SDK UI 主线程之间异步传输，禁止阻塞互等。原生 Promise 队列额外保证即使公共会话超时，未结束的 SDK join/leave 也不会被下一场 SDK 操作越过。

## 依赖与注册

```bash
ohpm install @gycrosskit/live-native@1.0.1
```


HAR 精确依赖腾讯 `atomicxcore 5.2.2`、`imsdk 9.1.7818`、`liteavsdk_professional 13.5.9128` 和 Kuikly Render `2.29.1`。腾讯依赖遵循其 Proprietary 许可，本仓库接线代码使用 Apache-2.0。当前源码 Kotlin 侧使用 Kuikly `2.29.1-2.0.21-ohos`，宿主须统一版本。

在宿主原有外部 View 注册回调中追加以下注册，保留其他组件已有注册。组件自己的 `createArkUIView()` 创建 ArkUI 节点，宿主无需另接 Builder：

```ts
import { ViewsRegisterEntry } from '@kuikly-open/render';
import { GycLiveClient, GycLiveView } from '@gycrosskit/live-native';

ViewsRegisterEntry.registerViewCreator(GycLiveView.VIEW_NAME, () => new GycLiveView());
```

宿主在已同意隐私后，把服务端 UserSig、业务用户与真实 Ability Context 交给 `await GycLiveClient.login(sdkAppId, userId, userSig, context, nickname, avatarUrl)`。组件核对 IM 实际用户、IM/AtomicX 登录状态和 SDKAppId；异步昵称/头像不参与认证，不在客户端生成 UserSig。

在 Ability `onForeground()` / `onBackground()` 分别调用 `GycLiveClient.setForeground(true/false, this.context)`，创建和销毁时也报告该 Context 为 false。默认 false，首次入房及列表预览必须在前台；本地修订的完整观看保留已建立的会话，不因普通后台重新退房。多宿主必须各自上报：主页面前台不能代表直播宿主前台。当前按 `bundleName/moduleName/Ability name` 区分 singleton；不保证同类多实例隔离。旧单 Ability 可继续使用无 Context 调用。ArkUI 组件另同步可见区域与移除事件。注销/切换账号先移除当前观看节点，再 `await GycLiveClient.logout()`；同一已就绪身份重复 login 只更新资料，不离房；组件拥有的旧身份切换时先清理/注销。借用的外部 IM 身份不会被本组件注销，外部账号必须由原 owner 清理；无法从 AtomicX 确认匹配 SDKAppId 的外部 IM 身份也拒绝借用。

Kotlin 继续使用既有 `io.github.gycrosskit.livesdk.kuikly.LiveVideo(liveId, preview, active, onEvent, onController)` 或 raw DSL。业务 UI 和房间 ID 由宿主提供，完整观看支持弹幕、点赞、观众列表/人数、公告、关播、被踢和禁言事件。

## 预览与限制

静音预览使用独立 `AtomicPlayer` 和 `livekit://liveid=<房间ID>`，不调用完整 `joinLive`。房间 ID 沿用腾讯接口原值，不自行改变编码。这一播放协议来自腾讯 [live-v2 官方播放器](https://github.com/Tencent-RTC/TUIKit_Android/blob/68fb94e5861bced0270ec881a084e78e48b8681d/live-v2/tuilivekit/src/main/java/com/trtc/uikit/livekit/player/PlayerViewImpl.kt#L142)；Android 的示例不能证明鸿蒙同协议可用。当前房间的鸿蒙原生 AtomicPlayer 对照无音画，预览仍有风险，详见[腾讯反馈材料](../../docs/腾讯鸿蒙AtomicPlayer问题反馈.md)。完整观看候选使用 `joinLive` 的主 TRTC，通过公开 `startRemoteView` 绑定真实 owner 与 `liteavsdk` XComponent，不额外进房；进房回执仍不代表已显示首帧。

预览仍使用腾讯 `5.2.2` 内部导出的 `AtomicLiveViewImpl`，上游没有兼容性承诺，因此锁定精确版本；完整观看不使用该内部节点。预览播放状态、人数与禁言的 `@Observed` 标量没有独立公开订阅入口，播放期间每 250ms 检查变化；消息和观众列表使用 SDK 数据源监听，停止时全部撤销。

本轮不开放连麦、PK、开播；原始 IM 由组件 GycLiveModule 按调用方群白名单投递，业务消息解析留宿主。本地未发布候选已接关注/粉丝和业务点赞 reporter；关注复用公共状态机，成功点赞批次由挂载前注入的 `KuiklyLiveView.likeReporter` 交给宿主。已发布 HAR 不支持系统 PiP；本地候选新增系统 PiP，编译与设备状态见功能文档。播放器错误发布 `playbackError`，不把普通断流当作真实关播。

## 本地候选的系统 PiP 接线

组件通过原视频节点的稳定 XComponentController 迁移同一 Surface，不新增媒体进房。宿主把
`GycLiveClient.observePictureInPicture(onChanged, onClosed)` 转接到公共页面的真实状态/关闭观察入口，
页面移除时执行返回的取消函数。可见前台页面的恢复命令调用 `await GycLiveClient.restorePictureInPicture()`；
后台点击浮窗展开由系统恢复页面，组件不构造宿主 Ability Want。

`enterPictureInPicture` 回执只表示系统启动请求受理；快照以真实 `STARTED/STOPPED` 回调为准。
命令必须携带布尔 `wideContent`：横向画布为 1280×720（16:9），标准竖向画布为 720×1280（9:16），与 Android/iOS 的房间布局合同一致。它不是源视频分辨率；主 TRTC 使用 Fit 保留视频比例，比例不同时允许留边。不能省略内容尺寸，依赖临时 XComponent 大小决定浮窗比例。
PiP 期间同一 XComponent 也保持目标比例；公共预布局会铺满原窗口，仅设置浮窗尺寸仍会把竖屏 Surface 压进横窗。展开后等待所属页面可见且前台恢复再解除比例约束，关闭、失败或会话释放时清理。
`updatePictureInPicture(true)` 是宿主预布局提示，不伪造已进入状态。普通后台保留完整观看会话、停止列表预览；单 Ability 宿主在 View 创建后设置 `pictureInPictureHostManaged=true`，真实 PiP STARTED 后隐藏 Audience 子树并显示可操作 Root，不把整个应用退后台。
注销及释放须先等待本会话 PiP 收尾，再完成原 `leaveLive`。系统拒绝、不支持或会话已失效时返回失败。

单 UIAbility 宿主保留 Root/Audience 两个 Renderer。进入 PiP 后使用保留布局和 Renderer 的 `Visibility.Hidden` 隐藏整棵直播子树，不能只依赖透明度或 `HitTestMode.None`，否则子节点仍可能拦截首页触摸。展开恢复同一 Renderer/会话，关闭和注销只清理所属直播；系统最近任务保留一张应用卡片。单 Ability 分项回归已由用户确认，后续整套 Module 候选的设备验收另列。SDK 和组件不负责创建业务首页 Want。

SDK 离房失败会关闭账号门禁，避免声称 SDK 已空闲；自有账号须成功 logout 后重登，借用账号须交原 owner 恢复。未结算的 SDK Promise 会阻塞后续 SDK 操作，公共超时不代表 SDK 已停止。

## 最小真机验收

1. 测试环境登录观众账号，使用相同 SDKAppId / liveId，观看 Android/iOS 主播；核对实际画面和声音。
2. 三端互发文本及现有表情协议，核对原始内容、观众人数、禁言和点赞；SDK 成功与业务后台记账分别验收。
3. A→B→A 快速切房，退出发生在 join 在途、离房回执迟到及弱网时；旧消息、旧画面不能进入新房间。
4. 列表静音预览→完整观看→退出回列表，再切后台/前台、账号 A→注销→B；核对旧播放器释放及无后台声音。

本地真机参数保留在受控的忽略目录，不提交 UserSig、账号参数、签名或原始 SDK 日志。替身测试、SDK 声明类型检查、Kotlin 编译、HAR 编译、远程公开消费和真机验收分别记录，当前分项结果见[功能与平台差异](../../docs/功能与平台差异.md)，命令见[开发与验证](../../docs/开发与验证.md)。

远程 HAR 编译由源码 CI 的 `ohos-har` job 执行；使用华为 Linux x64 CLT 直链 `OHOS_CLT_URL` 和对应 SHA-256 `OHOS_CLT_SHA256`，SDK 必须匹配 API 22。本轮已核对官方 `6.0.2.670` 工具链，实际编译结果见开发与验证记录；产物归档不会发布到 ohpm 或创建 Release。

当前未发布候选提供 Kotlin `LiveSdkModule` 与 HAR `GycLiveModule`，注册名为 `GycLiveSdk`；宿主注入当前 UIAbilityContext，账号复用既有 GycLiveClient。原始 IM 回调复用 LiveImObserver，业务消息解析、页面导航和窗口策略留宿主。Renderer 配套为 2.29.1，不能混用旧 HAR。
