package io.github.gycrosskit.livesdk

/** 两端 AtomicX 观看会话向业务层上报的稳定终态，不暴露厂商错误或 Store 类型。 */
internal interface AtomicAudienceListener {
    /** 已获得共享会话执行权，开始调用腾讯 joinLive。 */
    fun onJoinStarted()

    /** 腾讯确认进房成功，App 此后才可请求直播业务接口。 */
    fun onJoinSucceeded()

    /** 进房调用失败或超时。 */
    fun onJoinFailed(code: Int, message: String)

    /** 直播不存在或已经结束，宿主应提示并退出。 */
    fun onLiveUnavailable(message: String = "")

    /** 观看期间被主播或管理员移出当前直播间。 */
    fun onKickedOut()

    /** 观看期间收到关播或解散事件。 */
    fun onLiveEnded()

    /** 当前登录观众的弹幕权限变化。 */
    fun onCurrentUserMessageDisabled(disabled: Boolean)

    /** 系统画中画实际状态变化；默认实现兼容不提供原生 PiP 回调的平台。 */
    fun onPictureInPictureChanged(enabled: Boolean) = Unit
}

/**
 * 脱离平台 SDK 类型的单条直播间消息快照。
 *
 * @property sequence SDK 消息序号；重连后可能复用，不得单独用于去重。
 * @property timestampSeconds 消息时间，Unix 时间戳，单位为秒。
 * @property senderId 发送者账号 ID。
 * @property senderName 发送者展示名，允许为空。
 * @property senderAvatarUrl 发送者头像 URL，允许为空。
 * @property content 文本内容；成员事件为空，由 shared 根据 [kind] 生成展示文案。
 * @property businessId SDK 自定义业务标识，无值时为空。
 * @property data SDK 自定义透传数据，无值时为空。
 * @property kind 消息的语义类型。
 */
data class LiveAudienceMessageSnapshot(
    val sequence: Long,
    val timestampSeconds: Double,
    val senderId: String,
    val senderName: String,
    val senderAvatarUrl: String,
    val content: String,
    val businessId: String = "",
    val data: String = "",
    val kind: LiveAudienceMessageKind = LiveAudienceMessageKind.TEXT,
)

/** 平台成员回调只携带语义类型，最终展示文案由 shared UI 按当前语言解析。 */
enum class LiveAudienceMessageKind {
    TEXT,
    MEMBER_JOINED,
    MEMBER_LEFT,
}

/**
 * 直播间用户的最小跨端快照。
 *
 * @property id 平台账号 ID。
 * @property name 展示名，允许为空。
 * @property avatarUrl 头像 URL，允许为空。
 */
data class LiveAudienceUserSnapshot(
    val id: String,
    val name: String,
    val avatarUrl: String,
)

/**
 * 直播间与主播关系的跨端快照。
 *
 * @property roomId 直播间 ID。
 * @property roomName 直播间名称。
 * @property owner 主播资料；尚未获得 LiveInfo 时为 `null`。
 * @property followVisible 当前账号是否可对主播执行关注操作。
 * @property followed 当前账号是否已关注主播。
 * @property followRequestRunning 关注或取消关注请求是否正在执行。
 * @property fansCount 主播粉丝数，未获得时为 `0`。
 */
data class LiveAudienceHostSnapshot(
    val roomId: String,
    val roomName: String,
    val owner: LiveAudienceUserSnapshot?,
    val followVisible: Boolean,
    val followed: Boolean,
    val followRequestRunning: Boolean,
    val fansCount: Long,
)

/**
 * shared 操作层所需的完整只读快照；Android/iOS 原生 SDK 都必须收敛到本模型。
 *
 * @property messages 最新的有界消息列表，已由模块内部去重。
 * @property introduction 直播简介或公告。
 * @property interactionReady 弹幕等互动 Store 是否已可用。
 * @property loading 原生观看会话是否仍在进房。
 * @property pictureInPicture 系统画中画的实际状态。
 * @property likeEffectSequence 点赞动效事件序号，每次收到有效点赞时递增。
 * @property host 当前直播间和主播快照。
 * @property audience 当前已获得资料的在线观众。
 * @property audienceCount SDK 报告的在线总数，不小于 [audience] 的数量。
 */
data class LiveAudienceContentSnapshot(
    val messages: List<LiveAudienceMessageSnapshot>,
    val introduction: String,
    val interactionReady: Boolean,
    val loading: Boolean,
    val pictureInPicture: Boolean,
    val likeEffectSequence: Long,
    val host: LiveAudienceHostSnapshot,
    val audience: List<LiveAudienceUserSnapshot>,
    val audienceCount: Int,
) {
    companion object {
        /** 原生观看控件尚未安装时提供稳定空状态，避免 shared 感知平台初始化时序。 */
        val Empty = LiveAudienceContentSnapshot(
            messages = emptyList(),
            introduction = "",
            interactionReady = false,
            loading = true,
            pictureInPicture = false,
            likeEffectSequence = 0L,
            host = LiveAudienceHostSnapshot(
                roomId = "",
                roomName = "",
                owner = null,
                followVisible = false,
                followed = false,
                followRequestRunning = false,
                fansCount = 0L,
            ),
            audience = emptyList(),
            audienceCount = 0,
        )
    }
}

/** 直播列表预览状态不包含平台 View 或厂商错误对象。 */
enum class LivePreviewState {
    COVER,
    LOADING,
    PLAYING,
    FAILED,
}
