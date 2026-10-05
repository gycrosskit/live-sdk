package io.github.gycrosskit.livesdk

import platform.UIKit.UIView

/** Swift AtomicX 操作完成回调；接口由 Shared 传递导出，具体 SDK 类型只存在于 iosApp。 */
interface IosLiveOperationCallback {
    /** 操作在 Main 完成；上层仍需校验当前会话代次。 */
    fun onSuccess()

    /**
     * Main 返回平台错误码和原始描述；业务提示由宿主决定。
     *
     * @param code 平台错误码；组件自身失败可使用负值。
     * @param message 当前事件的原始文本；展示与脱敏策略由宿主决定。
     */
    fun onFailure(code: Int, message: String)
}

/** Swift IM 查询布尔结果时使用的稳定回调，不导出 V2TIM 类型。 */
interface IosLiveBooleanCallback {
    /**
     * Main 返回已确认的关注状态。
     *
     * @param value 已确认的关注状态，true 表示已关注。
     */
    fun onSuccess(value: Boolean)

    /**
     * Main 返回平台错误码和原始描述；业务提示由宿主决定。
     *
     * @param code 平台错误码；组件自身失败可使用负值。
     * @param message 当前事件的原始文本；展示与脱敏策略由宿主决定。
     */
    fun onFailure(code: Int, message: String)
}

/** Swift IM 查询计数结果时使用的稳定回调。 */
interface IosLiveLongCallback {
    /**
     * Main 返回查询计数，单位为人数。
     *
     * @param value 查询计数，单位为人数。
     */
    fun onSuccess(value: Long)

    /**
     * Main 返回平台错误码和原始描述；业务提示由宿主决定。
     *
     * @param code 平台错误码；组件自身失败可使用负值。
     * @param message 当前事件的原始文本；展示与脱敏策略由宿主决定。
     */
    fun onFailure(code: Int, message: String)
}

/** Swift 播放器把官方 SDK 事件转换为 live-sdk 已定义的稳定语义。 */
interface IosAudiencePlayerObserver {
    /** SDK 已确认进房，之后互动资源才可使用。 */
    fun onJoinSucceeded()

    /**
     * 进房成功后返回的直播信息，用于宽内容模式展示介绍，避免页面重复请求 SDK 数据。
     *
     * @param roomId 腾讯直播间 ID。
     * @param liveName SDK 直播名称，可为空。
     * @param notice 直播公告，可为空。
     * @param ownerId 主播账号 ID。
     * @param ownerName 主播展示名，可为空。
     * @param ownerAvatarUrl 主播头像 URL，可为空。
     */
    fun onLiveInfo(
        roomId: String,
        liveName: String,
        notice: String,
        ownerId: String,
        ownerName: String,
        ownerAvatarUrl: String,
    )

    /**
     * SDK 进房失败，交共用状态机统一清理。
     *
     * @param code 平台错误码；组件自身失败可使用负值。
     * @param message 当前事件的原始文本；展示与脱敏策略由宿主决定。
     */
    fun onJoinFailed(code: Int, message: String)

    /** 观看期间的真实关播事件，不能由暂时断流推断。 */
    fun onLiveEnded()

    /**
     * 进房前直播不可用，交宿主提示并退出。
     *
     * @param message 当前事件的原始文本；展示与脱敏策略由宿主决定。
     */
    fun onLiveUnavailable(message: String)

    /** 观看期间被主播或管理员移出当前直播间；SDK 原始文案不进入业务 UI。 */
    fun onKickedOut()

    /** 当前房间弹幕/观众等 Store 已绑定。 */
    fun onInteractionReady()

    /**
     * 转交一条文本弹幕；timestampSeconds 为 Unix 秒，sequence 不能单独用于去重。
     *
     * @param sequence SDK 消息序号，重连可能复用，不能单独去重。
     * @param timestampSeconds Unix 时间戳，单位为秒。
     * @param senderId 发送者账号 ID。
     * @param senderName 发送者展示名，可为空。
     * @param senderAvatarUrl 发送者头像 URL，可为空。
     * @param content 原始弹幕文本。
     */
    fun onBarrageReceived(
        sequence: Long,
        timestampSeconds: Double,
        senderId: String,
        senderName: String,
        senderAvatarUrl: String,
        content: String,
    )

    /**
     * 转交远端点赞事件，用于动效；不是本地业务上报回执。
     *
     * @param count 本批点赞数，单位为次，应大于 0。
     */
    fun onLikesReceived(count: Int)

    /**
     * 转交同批观众平行数组与总数；缺少昵称或头像时 Kotlin 使用空值。
     *
     * @param userIds 同批观众账号列表。
     * @param userNames 与 userIds 对齐的展示名列表，缺项以空值处理。
     * @param userAvatarUrls 与 userIds 对齐的头像 URL 列表，缺项以空值处理。
     * @param count SDK 报告的在线总人数，可大于已加载列表长度。
     */
    fun onAudienceChanged(
        userIds: List<String>,
        userNames: List<String>,
        userAvatarUrls: List<String>,
        count: Int,
    )

    /**
     * AtomicX 观众 Store 的成员进退房事件；Swift 不拼接最终展示文案。
     *
     * @param joined true 为成员加入，false 为成员离开。
     * @param userId 腾讯账号 ID，按原值匹配，不替换其他账号。
     * @param userName 成员展示名，可为空。
     * @param userAvatarUrl 成员头像 URL，可为空。
     * @param timestampSeconds Unix 时间戳，单位为秒。
     */
    fun onMemberChanged(
        joined: Boolean,
        userId: String,
        userName: String,
        userAvatarUrl: String,
        timestampSeconds: Double,
    )

    /**
     * 指定观众的弹幕权限变化；live-sdk 会过滤为当前登录用户。
     *
     * @param userId 腾讯账号 ID，按原值匹配，不替换其他账号。
     * @param disabled true 表示当前账号不能发送弹幕。
     */
    fun onAudienceMessageDisabled(userId: String, disabled: Boolean)

    /**
     * RoomEngine 实验 PiP 接口回执；成功不能证明系统浮窗已开始或可见。
     *
     * @param enabled 平台回报或宿主同步的 PiP 标记；不能独立证明浮窗可见。
     */
    fun onPictureInPictureChanged(enabled: Boolean)
}

/** Swift 列表预览把 AtomicX 播放状态收敛为 shared 可消费的最小语义。 */
interface IosLivePreviewObserver {
    /** 原生预览正在准备或缓冲。 */
    fun onLoading()

    /** 原生 SDK 确认预览开始播放。 */
    fun onPlaying()

    /**
     * 原生预览失败，控制器停止并发布 FAILED。
     *
     * @param code 平台错误码；组件自身失败可使用负值。
     * @param message 当前事件的原始文本；展示与脱敏策略由宿主决定。
     */
    fun onFailed(code: Int, message: String)
}

/** Swift 腾讯 IM 监听器向 shared 收敛的最小事件集合；消息正文仍由 shared 按后端契约解析。 */
interface IosLiveImObserver {
    /**
     * 绑定群组的 UTF-8 透传正文，由宿主业务协议解析。
     *
     * @param groupId 当前绑定中的腾讯群组 ID。
     * @param payload UTF-8 透传正文，组件不解释业务字段。
     */
    fun onRestCustomData(groupId: String, payload: String)

    /**
     * 当前账号被移出绑定群组。
     *
     * @param groupId 当前绑定中的腾讯群组 ID。
     * @param operatorUserId 移出操作者账号，SDK 未提供时为空。
     */
    fun onCurrentUserRemoved(groupId: String, operatorUserId: String)

    /**
     * 绑定群组已解散。
     *
     * @param groupId 当前绑定中的腾讯群组 ID。
     */
    fun onGroupDismissed(groupId: String)

    /** 账号被踢下线，原生先撤销绑定再交付终态。 */
    fun onKickedOffline()

    /** 凭据过期，刷新与重新绑定由宿主负责。 */
    fun onUserSigExpired()
}

/**
 * iosApp 对 AtomicXCore 的唯一桥接边界。
 *
 * AtomicXCore 的 Store 主要以 Swift API 暴露，不能让 Kotlin/Native 直接依赖其二进制类型。Swift 只实现登录、
 * 进退房、互动命令和 `UIView` 生命周期；直播间可见操作层、提示和业务入口由 shared 统一维护。
 * 原生实现须把事件和完成回调调度到 Main，Kotlin 状态机不另建厂商线程。
 */
interface IosLiveSdkBridge {
    /**
     * 同步查询 SDK 与 IM 真实账号；主线程外实现可能短暂等待 Main。
     *
     * @param userId 腾讯账号 ID，按原值匹配，不替换其他账号。
     */
    fun isLoggedInAs(userId: String): Boolean

    /**
     * 在 Main 更新匹配账号资料；空昵称回退 userId。
     *
     * @param userId 腾讯账号 ID，按原值匹配，不替换其他账号。
     * @param nickname 展示昵称；Swift 空字符串回退 userId，空白文本按原值保留。
     * @param avatarUrl 头像 URL，可为空。
     */
    fun updateProfile(userId: String, nickname: String, avatarUrl: String)

    /**
     * 使用服务端 UserSig 登录或借用匹配身份；不得替换外部腾讯账号，回调在 Main。
     *
     * @param sdkAppId 腾讯 SDK 应用 ID，必须大于 0。
     * @param userId 腾讯账号 ID，按原值匹配，不替换其他账号。
     * @param userSig 服务端签发的 UserSig，必须非空白，按原值交给 SDK。
     * @param nickname 展示昵称；Swift 空字符串回退 userId，空白文本按原值保留。
     * @param avatarUrl 头像 URL，可为空。
     * @param callback 当前操作的完成回执；Native 结果在 Main 交付。
     */
    fun login(
        sdkAppId: Int,
        userId: String,
        userSig: String,
        nickname: String,
        avatarUrl: String,
        callback: IosLiveOperationCallback,
    )

    /**
     * 清理本组件资源；仅自己取得的 SDK 账号可注销，借用身份保留。
     *
     * @param callback 当前操作的完成回执；Native 结果在 Main 交付。
     */
    fun logout(callback: IosLiveOperationCallback)

    /**
     * Main 创建当前唯一静音预览，不进入完整房间；observer 回调在 Main。
     *
     * @param liveId 当前实例的腾讯直播间 ID，实例不可跨房间复用。
     * @param observer 当前绑定的事件监听器，替换绑定后旧监听失效。
     */
    fun makePreviewView(liveId: String, observer: IosLivePreviewObserver): UIView

    /**
     * 仅清理仍归当前预览所有的 View，迟到 dispose 不得误停新预览。
     *
     * @param view 当前实例拥有的原生 View，以对象身份判断归属。
     */
    fun releasePreviewView(view: UIView)

    /** Main 幂等停止当前预览。后台调用须等待实际清理后再执行后续 SDK 操作。 */
    fun stopPreview()

    /**
     * 替换原生群/账号 listener；旧监听身份立即失效，事件回 Main。
     *
     * @param groupIds 需要监听的群组 ID；空白与重复值由绑定实现过滤。
     * @param observer 当前绑定的事件监听器，替换绑定后旧监听失效。
     */
    fun connectLiveIm(groupIds: List<String>, observer: IosLiveImObserver)

    /** 撤销当前 IM 绑定及 listener，不注销腾讯账号。 */
    fun disconnectLiveIm()

    /** 同步读取 SDK 真实当前账号，无登录时返回空字符串。 */
    fun currentUserId(): String

    /**
     * 发送当前房间原始文本弹幕；未就绪按失败回执，结果回 Main。
     *
     * @param message 原始弹幕文本，编码和用户文案由宿主决定。
     * @param callback 当前操作的完成回执；Native 结果在 Main 交付。
     */
    fun sendBarrage(message: String, callback: IosLiveOperationCallback)

    /**
     * 发送 common 合并后的正数批次，SDK 成功才触发业务上报。
     *
     * @param count 本批点赞数，单位为次，应大于 0。
     * @param callback 当前操作的完成回执；Native 结果在 Main 交付。
     */
    fun sendLike(count: Int, callback: IosLiveOperationCallback)

    /**
     * 查询指定主播的真实关注状态，回 Main。
     *
     * @param userId 腾讯账号 ID，按原值匹配，不替换其他账号。
     * @param callback 当前操作的完成回执；Native 结果在 Main 交付。
     */
    fun checkFollowed(userId: String, callback: IosLiveBooleanCallback)

    /**
     * 设置明确关注目标，避免超时重试时反复 toggle；结果回 Main。
     *
     * @param userId 腾讯账号 ID，按原值匹配，不替换其他账号。
     * @param followed 明确的关注目标，true 关注，false 取消。
     * @param callback 当前操作的完成回执；Native 结果在 Main 交付。
     */
    fun updateFollow(userId: String, followed: Boolean, callback: IosLiveOperationCallback)

    /**
     * 查询指定主播粉丝人数，结果回 Main。
     *
     * @param userId 腾讯账号 ID，按原值匹配，不替换其他账号。
     * @param callback 当前操作的完成回执；Native 结果在 Main 交付。
     */
    fun fetchFans(userId: String, callback: IosLiveLongCallback)

    /** 请求刷新已绑定房间观众，后续通过 observer 发布快照。 */
    fun refreshAudience()

    /**
     * 请求 RoomEngine 实验 PiP；返回值表示是否提交，observer 返回接口回执，系统浮窗仍需设备验收。
     *
     * @param wideContent true 使用横向画布，false 使用竖向画布。
     */
    fun enterPictureInPicture(wideContent: Boolean): Boolean

    /**
     * 取得共用会话令牌后在 Main 创建唯一观看 View 并发起进房。
     *
     * @param liveId 当前实例的腾讯直播间 ID，实例不可跨房间复用。
     * @param observer 当前绑定的事件监听器，替换绑定后旧监听失效。
     */
    fun makeAudienceView(liveId: String, observer: IosAudiencePlayerObserver): UIView

    /**
     * 离房完成后才回调，common 会据此放行下一场观看或列表预览。
     *
     * @param view 当前实例拥有的原生 View，以对象身份判断归属。
     * @param callback 当前操作的完成回执；Native 结果在 Main 交付。
     */
    fun releaseAudienceView(view: UIView, callback: IosLiveOperationCallback)
}
