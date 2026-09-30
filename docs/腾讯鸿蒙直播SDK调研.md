# 腾讯鸿蒙直播 SDK 调研

核对日期：2026-09-30。结论：Android/iOS 可通过 Kuikly Native DSL 复用已有 AtomicX 观看组件；当前已核对的 HarmonyOS 官方 SDK 不能直接等价实现此组件的 `liveId` 观看协议。不能将 TRTC 房间号或播放 URL 当作 AtomicX `joinLive` 的替代。

## 当前决策：暂停鸿蒙直播实现

2026-09-30，用户转述腾讯人员说明鸿蒙 SDK 计划节后发布，并决定先不实现。该时间为用户提供的厂商沟通信息，尚无本轮核验的公开发布公告或确定版本。等待正式 SDK 可取得后，再核对直播 API、房间协议和 Android/iOS 互通；当前不开发 TRTC 替代适配。已完成的 Android/iOS 组件保留。

## 官方证据

- [TRTC HarmonyOS 集成](https://cloud.tencent.com/document/product/647/130532)：提供 `enterRoom`、`startRemoteView`、`exitRoom` 与 XComponent。证明音视频引擎可用，未证明 AtomicX 直播房间、观众和互动 Store 可用。
- [LiteAV 文档入口](https://cloud.tencent.com/document/product/454/130269)：本次页面请求被拦截，未据此宣称能力。
- [IM HarmonyOS 接入](https://cloud.tencent.com/document/product/269/125128)：该页面是 Chat ArkUI 接入，示例依赖 `@tencentcloud/atomicxcore`，提供登录与消息能力；不是直播产品的接入证明。
- [腾讯 TUIKit_Harmony](https://github.com/Tencent-RTC/TUIKit_Harmony)：本次官方主分支树包含 call/chat/room，未发现直播 Store 或 LiveCoreView 实现。
- 官方公开包：[@tencentcloud/atomicxcore 5.1.0 HAR](https://ohpm.openharmony.cn/ohpm/@tencentcloud/atomicxcore/-/atomicxcore-5.1.0.har)，原厂 Tencent Cloud Terminal R&D Center，Proprietary。SHA-256：`f191e85359da4012d8c4dc4587eb38fa8c0801280ec67f1302bcb1700b9f89ab`。
- 同时核对 4.2.0 HAR 的公共入口；也没有本组件所需的直播 API。只承诺本次实际核对的包版本，不能外推到未来 SDK。

## 能力对照

| live-sdk 所需能力 | HarmonyOS 官方包核对结果 | 影响 |
| --- | --- | --- |
| SDKAppId/UserId/UserSig 登录、账号事件 | `LoginStore` 提供 login/logout，LoginEvent 提供踢下线/过期 | SDK 账号能力可用，业务凭据仍由宿主提供 |
| IM 收发与群事件 | 包入口导出原厂 `@tencentcloud/imsdk` | 有 IM，未证明等价直播 Store/自定义业务消息契约 |
| LiveListStore.joinLive/leaveLive | 5.1.0/4.2.0 公共入口和类型文件未发现 | 无法凭现有 liveId 进入现有 AtomicX 直播 |
| LiveCoreView 列表预览 | 未发现 LiveCoreView/startPreviewLiveStream | 无法等价预览，不能伪造 PLAYING |
| LiveAudienceStore/BarrageStore/LikeStore | 未发现 | 观众列表、弹幕、点赞及禁言事件缺少等价 SDK 入口 |
| TUIRoomEngine SDK/API | 所给官方文档及上述包未找到可用入口；猜测的两个 ohpm 包名返回404 | 404仅表示所查询包名不存在，不等于腾讯所有分发渠道不存在 |
| TRTC 远端播放 | 官方 TRTC 文档确认存在 | 需要宿主提供明确房间号/主播ID及服务端兼容契约，当前不替宿主推导 |
| 已有 Harmony openLiveRoom | 应用当前为 unavailable | 没有已接入 SDK 实现可供迁移 |

## RoomStore 与直播协议的区别

5.1.0 的 `api/room/RoomStore.d.ets` 中 `RoomType` 仅有 `standard=1` 和 `webinar=2`，没有 LIVE；`joinRoom(roomID, roomType, password)` 面向标准会议/研讨会。[官方 Harmony RoomStore 文档](https://cloud.tencent.com/document/product/269/136962)归属“视频会议”，[Harmony RoomKit 接入](https://cloud.tencent.com/document/product/647/131427)说明当前只能在 Harmony 创建标准会议，研讨会需要 Web 创建。该 SDK 具备房间/观众基础能力，但没有证据证明这些 roomID 与现有 LiveListStore 的 liveID、成员和互动协议可互通，因此仍不能直接替换。

## 后续解除阻断条件

腾讯提供 HarmonyOS LiveListStore/LiveCoreView 等价 API，或验证现有房间的 TRTC/直播播放协议，并取得 liveId 与真实 TRTC 房间/主播、成员/弹幕/点赞/禁言/退出的服务端契约。届时实现实际 HAR 和 OHOS target；当前不新增假成功的鸿蒙 View 或用 SDK 空实现代替。

当前实作范围为 Android/iOS，原 CMP 接口继续通过根坐标使用。`live-core` 不依赖 CMP/Kuikly；`live-kuikly` 使用 Kuikly Native DSL，独立原生视频节点共享同一会话、StateFlow 快照及 SDK 账号。此结论不是三端等价或真实房间验收。

## TRTC 对齐备选方案（已暂停，不执行）

用户补充腾讯智能客服建议以 TRTC 鸿蒙 SDK 自建 UI。按官方 API，这条路线可以实现媒体层；对齐现有直播还需要补齐业务房间与成员协议。客服给的 `647/104842` 当前为 TUIRoomKit 的开通服务页面；实际参考 [HarmonyOS 接入](https://cloud.tencent.com/document/product/647/130532) 与 [HarmonyOS TRTCCloud API](https://cloud.tencent.com/document/product/647/129076)。

### 上层保持同一套契约

- `live-core` 继续持有 UI 无关的观看状态、错误、退出时序和快照；Kuikly 页面消费同样的状态与事件。
- Android/iOS 继续由 AtomicX 完成原有直播；鸿蒙媒体层使用 TRTC + XComponent，互动层使用 IM 与已确认的服务端协议。底层 SDK 可以不同，公开能力与状态语义必须一致。
- SDKAppId、观众 SDK userId 与 UserSig 沿用宿主已有账号链路；不在库内生成 UserSig、建立第二套业务登录或保存 SecretKey。

### 先取得准确的房间接入数据

当前接入项目 `LiveRoom.roomID` 为业务主键，`roomCode` 经 `sdkRoomId` 转为腾讯 liveId；`LiveRoomRoute` 明确业务 ID 与直播群标识分开。模型没有提供已验证的 TRTC transport 参数。后端或腾讯需确定：

| 字段/规则 | 为什么需要 |
| --- | --- |
| liveId 与实际 TRTC roomId/strRoomId 的映射及房型 | [TRTC 基本概念](https://cloud.tencent.com/document/product/647/46351)明确数字 `123` 与字符串 `"123"` 是不同房间；不能从业务 ID 转型、加前缀或做 hash 推导 |
| 主播 SDK userId、视频/屏幕流标识 | 后端 PublisherID 是业务 ID，不能未经现有映射核对就替代媒体订阅标识 |
| scene 与 role | 鸿蒙观众使用已确认的 LIVE 场景和 Audience 角色，与主播房间设置相匹配 |
| privateMapKey（若开启房间权限控制） | UserSig 只证明账号，房间另有权限时须由服务端签发票据，不在客户端绕过 |
| IM 群标识、AtomicX 成员入场/离场及互动契约 | 收到 IM 消息与被登记为 AtomicX 观众是不同步骤，直接 TRTC 进房不证明完成直播成员注册 |
| 房间有效状态与业务观看资格 | TRTC 对不存在的房间可能自动创建空房间，进房成功不证明直播存在，更不替代应用观看权限 |

官方 [get_room_info](https://cloud.tencent.com/document/product/647/110038) 可返回 RoomId、房主及业务房间信息，公开应答没有 TRTC 房间凭证或播放 URL，不能单凭它生成 transport 参数。管理员 REST 调用留在服务端，客户端不持有管理员 UserSig。

官方 iOS 列表示例的 `Live_`/`voice_` 是直播类型命名约定，见[直播间列表](https://intl.cloud.tencent.com/zh/document/product/1071/76792)，不能据此给任意 roomCode 再加前缀作为 TRTC 映射。

### 分两步验收

**第一步：基础观看互通。** 在同一 SDKAppId 下，由已在 Android/iOS 使用 AtomicX 开播的测试主播提供真实房间。鸿蒙以 Audience 调用 `enterRoom`，以 `XComponentType.SURFACE` / `libraryname: liteavsdk` 承载画面，通过 `startRemoteView(userId, Big, viewId)` 订阅主播。进房回调只更新进房状态，收到首帧/实际音频播放回调才确认播放；错误、掉线、隐藏、注销与退出撤销旧实例回调，退出等待 `onExitRoom` 后清理。先证明同房、同主播、声音、首帧、退出与重新进入，再包装为正式 HAR 的统一入口。

基础媒体 wrapper 可先进行 API/构建验证，但只输入已确认的显式 TRTC 参数；它不承诺 `joinLive(liveId)` 等价。没有真实房间验收时不发布为现有直播已适配。

原厂包为 `@tencentcloud/liteavsdk_professional`。快速接入文档的 13.2 配置要求 API 17、HarmonyOS 5.0.5、DevEco 6.0.0.858；封装时锁定实际核对的包版本并按宿主 SDK 验证，不能只据文档版本写成已经编译。单主播订阅也不等于 AtomicX 连麦/PK/OBS/混流全部可用，需按房间实际流形态选择订阅。回调见 [Harmony TRTCCloudCallback](https://cloud.tencent.com/document/product/647/129077)。

**第二步：互动与成员对齐。** IM 接入相同账号与确认的直播群；应用的签到、红包、任务、问卷 `Type/Data` 继续交给原 `shared-business/LiveImEventParser`，不搬入通用库。弹幕、SDK 聚合点赞、观众列表、禁言、踢人、关播和列表预览逐项核对 AtomicX 协议；业务 HTTP 点赞上报可复用，不能据此宣称 SDK 点赞状态已互通。

[TRTC FAQ](https://cloud.tencent.com/document/faq/647/43020)说明远端用户进退回调面向有上行能力的用户，不能靠它们重建完整观众列表/人数。列表预览直接 TRTC 进房也可能与 AtomicX 原有预览在成员登记、订阅和计费上不同，应单独核对。连麦、礼物、主播开播和 PK 不在当前观看库范围。

以上为先前评估的备选路线，当前按用户决定暂停，等待腾讯正式 SDK。本轮未新增 OHOS 实现。

给腾讯或后端确认的最短清单：

1. AtomicX liveId 对应的真实 TRTC roomId 类型、scene 和获取方式是什么？
2. 直接 TRTC Audience 是否可加入现有房间，是否需 privateMapKey 或先注册 AtomicX 成员？
3. 主播/混流的 SDK userId 与流类型是什么，OBS、连麦或 PK 时是否变化？
4. 鸿蒙成员的加入/退出、人数、禁言、踢人、关播、弹幕与点赞怎样和现有 AtomicX 互通？

若无法开放直接 TRTC 观众接入，可评估由服务端提供真实旁路/混流播放地址，见[转推 CDN](https://cloud.tencent.com/document/product/647/84721)。不能从 liveId 拼接播放 URL；该路线仍需转推配置、播放器接入与互动协议验证。
