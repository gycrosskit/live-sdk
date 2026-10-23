# HarmonyOS AtomicPlayer 同房间黑屏无声反馈

核对日期：2026-10-23。当前问题待腾讯确认，不预先归类为 SDK 缺陷或配置错误。完整三端边界与组件方案见[功能与平台差异](功能与平台差异.md#本地黑屏根因与三端对齐边界2026-10-23)。

## 环境

| 项目 | 固定版本 / 条件 |
| --- | --- |
| `@tencentcloud/atomicxcore` | 5.2.2 |
| IM | 9.1.7818 |
| `@tencentcloud/liteavsdk_professional` | 13.5.9128 |
| 设备 | Huawei PLA-AL10，API 26 |
| 原生验证 | 纯 ArkUI，无 Kuikly、live-sdk 或宿主业务页面 |
| 对照条件 | 同一有效 SDKAppID / UserID / UserSig、同一直播房间；真实发布者从 SDK 回调获取，不拼接账号 |

## 原生复现

AtomicPlayer Demo 实际顺序如下；参数由本地受控输入提供，不包含在本文或仓库中。

1. `LoginStore.shared().login(...)` 成功。
2. 创建 `AtomicPlayer` 和 playView 类型 `AtomicLiveView`，调用 `setView`，由 `AtomicLiveViewImpl` 挂载。
3. `await player.joinChannel(liveID)` 成功，收到 `JoinChannelSucceeded`。
4. `player.startPlay('livekit://liveid=' + liveID)`。

实际：播放器处于 Loading，视频尺寸 0×0、FPS 0，没有该播放器的音视频首帧；用户确认黑屏无声。SDK 原始 `queryLayout` 返回空字符串。日志可见无 CDN 后使用 `livekit_<SDKAppID>_feedback_<liveID>` 目标，该目标未收到媒体。

另一次诊断把同一 AtomicPlayer 直接绑定已创建、非零尺寸的 Surface，绑定成功仍黑屏。这只用于隔离问题，不作为推荐的公开接入方式。

## TRTC 原生对照

仅保留 LiteAV Professional 13.5.9128，使用公开 `getTRTCShareInstance`、`enterRoom`（LIVE、Audience、roomId=0、实际 strRoomId），在 `onUserVideoAvailable` 收到真实发布者后调用 `startRemoteView`，绑定带 `libraryname: 'liteavsdk'` 的 XComponent。

实际：进房成功，实际发布者视频可用、音频首帧、视频首帧 1280×720；用户确认画面和声音均正常。该发布者与 AtomicX `joinLive` 回执中的 owner 一致。

旧宿主还存在一个容易误判的现象：`joinLive` 主 TRTC 已解码主播视频并播放声音，但没有 renderer；另一个 AtomicPlayer 的 feedback 目标无媒体。因此全进程的首帧解码日志不能视为 AtomicPlayer 播放成功。

本地 live-sdk 候选随后改为绑定 `joinLive` 的主 TRTC 实际 owner，未额外进房，和合康宿主也已由用户确认画面、声音正常。该结果进一步支持上述媒体链路判断，但不能替代腾讯对 AtomicPlayer 设计及修复的说明。

## 请腾讯确认

1. 5.2.2 在无 CDN 的上述房间选择 feedback 目标是否符合设计？它需要哪种开播方式、混流设置或云端配置？是否应回退到实际发布者流？
2. `setView → await joinChannel → startPlay` 的公开观看接线是否缺少必要步骤？请给出能消费相同房间的完整官方 ArkUI 示例。
3. 空 `queryLayout` 与 feedback 无媒体是否同一原因？是否为已知兼容问题，修复版本和回归条件是什么？
4. 在保留 `LiveListStore.joinLive/leaveLive` 时，公开 `getTRTCShareInstance` 绑定其实际 owner 是否为受支持的组合？升级是否保证共享实例及 callback 共存？
5. 不加入完整直播间的静音列表预览，应如何在此房间模式下实现？请分别说明观看和预览，避免以额外业务进房替代预览。

## 更新后的回归条件

同参数原生 AtomicPlayer Demo 音画正常后，再验证 live-sdk 宿主的首次进入、退出重入、前后台恢复、列表静音预览及预览与观看互斥；同时确认进房消息正常、不误报解除禁言。绑定实际 SDK 版本、源码和安装包摘要，不能仅用 SDK 更新或编译成功作为修复结论。

本文可直接提供腾讯；真实账号、UserSig、房间参数、签名和原始 SDK 日志未纳入公开材料。
