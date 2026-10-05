import UIKit

/// 原生操作回执；SDK 错误只交给宿主，不直接展示原始文案。
public protocol GycLiveOperationCallback {
    /// Main 上完成操作；观看清理成功不等于注销了借用的 SDK 账号。
    func onSuccess()
    /// Main 返回 SDK 错误码和原始描述，由宿主选择用户文案。
    /// - Parameter code: SDK 错误码，组件错误可使用负值。
    /// - Parameter message: 原始文本或平台错误描述，用户文案由宿主决定。
    func onFailure(code: Int32, message: String)
}

/// 不暴露 IM 类型的 Main 布尔结果回调。
public protocol GycLiveBooleanCallback {
    /// Main 返回已确认的关注状态。
    /// - Parameter value: 已确认的关注状态。
    func onSuccess(value: Bool)
    /// Main 返回 SDK 错误码和原始描述，由宿主选择用户文案。
    /// - Parameter code: SDK 错误码，组件错误可使用负值。
    /// - Parameter message: 原始文本或平台错误描述，用户文案由宿主决定。
    func onFailure(code: Int32, message: String)
}

/// 不暴露 IM 类型的 Main 人数回调。
public protocol GycLiveLongCallback {
    /// Main 返回人数查询结果。
    /// - Parameter value: 查询人数，单位为人。
    func onSuccess(value: Int64)
    /// Main 返回 SDK 错误码和原始描述，由宿主选择用户文案。
    /// - Parameter code: SDK 错误码，组件错误可使用负值。
    /// - Parameter message: 原始文本或平台错误描述，用户文案由宿主决定。
    func onFailure(code: Int32, message: String)
}

/// 不暴露 AtomicX 或 IM 类型，UIKit 应用和不同 KMP 导出模块均可接入。
public protocol GycAudiencePlayerObserver {
    /// SDK 已确认当前观看会话进房。
    func onJoinSucceeded()
    /// 当前房间与主播信息，来自进房回执，非宿主第二次请求。
    /// - Parameter roomId: 腾讯直播间 ID。
    /// - Parameter liveName: 直播名称，可为空。
    /// - Parameter notice: 直播公告，可为空。
    /// - Parameter ownerId: 主播账号 ID。
    /// - Parameter ownerName: 主播展示名，可为空。
    /// - Parameter ownerAvatarUrl: 主播头像 URL，可为空。
    func onLiveInfo(roomId: String, liveName: String, notice: String, ownerId: String,
                    ownerName: String, ownerAvatarUrl: String)
    /// 当前观看会话进房失败。
    /// - Parameter code: SDK 错误码，组件错误可使用负值。
    /// - Parameter message: 原始文本或平台错误描述，用户文案由宿主决定。
    func onJoinFailed(code: Int32, message: String)
    /// 当前房间真实关播事件。
    func onLiveEnded()
    /// 当前观众被主播或管理员移出。
    func onKickedOut()
    /// 当前房间互动 Store 已绑定。
    func onInteractionReady()
    /// 原始文本弹幕；timestampSeconds 是 Unix 秒，sequence 不可单独用于去重。
    /// - Parameter sequence: SDK 消息序号，重连可能复用，不可单独去重。
    /// - Parameter timestampSeconds: Unix 时间戳，单位为秒。
    /// - Parameter senderId: 发送者账号 ID。
    /// - Parameter senderName: 发送者展示名，可为空。
    /// - Parameter senderAvatarUrl: 发送者头像 URL，可为空。
    /// - Parameter content: 原始弹幕文本。
    func onBarrageReceived(sequence: Int64, timestampSeconds: Double, senderId: String,
                           senderName: String, senderAvatarUrl: String, content: String)
    /// 远端点赞事件计数，用于动效，不是本地提交的成功回执。
    /// - Parameter count: SDK 计数，单位为次。
    func onLikesReceived(count: Int32)
    /// 同批观众平行数组与在线总数；总数可能大于已加载列表长度。
    /// - Parameter userIds: 同批观众账号列表。
    /// - Parameter userNames: 与 userIds 对齐的展示名列表。
    /// - Parameter userAvatarUrls: 与 userIds 对齐的头像 URL 列表。
    /// - Parameter count: SDK 报告的在线总人数，可能大于已加载列表长度。
    func onAudienceChanged(userIds: [String], userNames: [String], userAvatarUrls: [String], count: Int32)
    /// 成员进退房事件，timestampSeconds 为 Unix 秒；文案由宿主生成。
    /// - Parameter joined: true 成员加入，false 成员离开。
    /// - Parameter userId: 腾讯账号 ID，按原值匹配。
    /// - Parameter userName: 成员展示名，可为空。
    /// - Parameter userAvatarUrl: 成员头像 URL，可为空。
    /// - Parameter timestampSeconds: Unix 时间戳，单位为秒。
    func onMemberChanged(joined: Bool, userId: String, userName: String,
                         userAvatarUrl: String, timestampSeconds: Double)
    /// 指定账号的弹幕权限变化，Kotlin 边界再过滤当前账号。
    /// - Parameter userId: 腾讯账号 ID，按原值匹配。
    /// - Parameter disabled: true 禁止该账号发送弹幕。
    func onAudienceMessageDisabled(userId: String, disabled: Bool)
    /// 会话关闭时清理 PiP 状态；实验接口准备成功不发布此事件。
    /// - Parameter enabled: 关闭会话为 false；系统启动/停止的实际状态由宿主生命周期同步到 Kotlin View。
    func onPictureInPictureChanged(enabled: Bool)
}

/// 当前唯一静音预览的 Main 状态事件。
public protocol GycLivePreviewObserver {
    /// 预览正在准备或缓冲。
    func onLoading()
    /// SDK 已确认静音预览开始播放。
    func onPlaying()
    /// 预览失败，平台错误交宿主诊断。
    /// - Parameter code: SDK 错误码，组件错误可使用负值。
    /// - Parameter message: 原始文本或平台错误描述，用户文案由宿主决定。
    func onFailed(code: Int32, message: String)
}

/// UTF-8 payload 原样交给宿主协议解析器；组件只做连接归属和群组过滤。
public protocol GycLiveImObserver {
    /// 绑定群组的原始 UTF-8 REST 透传数据。
    /// - Parameter groupId: 当前绑定群组 ID。
    /// - Parameter payload: UTF-8 透传正文，业务协议由宿主解析。
    func onRestCustomData(groupId: String, payload: String)
    /// 当前登录账号被移出绑定群组。
    /// - Parameter groupId: 当前绑定群组 ID。
    /// - Parameter operatorUserId: 移出操作者账号，SDK 未提供时为空。
    func onCurrentUserRemoved(groupId: String, operatorUserId: String)
    /// 当前绑定群组已解散。
    /// - Parameter groupId: 当前绑定群组 ID。
    func onGroupDismissed(groupId: String)
    /// 当前监听被踢下线，通知前先撤销绑定。
    func onKickedOffline()
    /// 当前监听凭据过期，宿主负责刷新。
    func onUserSigExpired()
}

/// 原生 SDK 适配日志级别，由宿主日志实现消费。
/// info 为流程诊断，warning 为可恢复异常，error 为操作失败。
public enum GycLiveLogLevel { case info, warning, error }
