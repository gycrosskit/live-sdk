package io.github.gycrosskit.livesdk

/**
 * 观看控件事件；缺省回调均为空操作，平台 actual 必须始终调用重组后的最新实例。
 *
 * @property onJoinStarted 获得共享会话执行权并开始调用原生进房。
 * @property onJoinSucceeded 原生 SDK 确认进房成功。
 * @property onJoinFailed 进房失败；参数为平台错误码和可展示描述。
 * @property onLiveUnavailable 直播不存在、已结束或当前账号无法观看。
 * @property onKickedOut 观看期间被主播或管理员移出当前直播间。
 * @property onLiveEnded 观看期间收到关播或解散事件。
 * @property onCurrentUserMessageDisabled 当前登录观众的弹幕权限变化。
 * @property onPictureInPictureChanged 平台 PiP 标记变化；Android 包含请求准备值与 Activity 回报，iOS 为实验接口回执，不能证明浮窗可见。
 */
class LivePlaybackCallbacks(
    val onJoinStarted: () -> Unit = {},
    val onJoinSucceeded: () -> Unit = {},
    val onJoinFailed: (code: Int, message: String) -> Unit = { _, _ -> },
    val onLiveUnavailable: (message: String) -> Unit = {},
    val onKickedOut: () -> Unit = {},
    val onLiveEnded: () -> Unit = {},
    val onCurrentUserMessageDisabled: (disabled: Boolean) -> Unit = {},
    val onPictureInPictureChanged: (enabled: Boolean) -> Unit = {},
)

/**
 * 把声明式回调转为原生会话监听器。
 *
 * [callbacks] 是 provider 而不是固定快照，以便原生 View 在 Compose 重组后继续调用最新业务回调。
 *
 * @param callbacks 事件发生时读取最新回调的 provider，避免捕获过期组合快照。
 */
class LivePlaybackCallbackListener(
    private val callbacks: () -> LivePlaybackCallbacks,
) : AtomicAudienceListener {
    override fun onJoinStarted() = callbacks().onJoinStarted()

    override fun onJoinSucceeded() = callbacks().onJoinSucceeded()

    override fun onJoinFailed(code: Int, message: String) =
        callbacks().onJoinFailed(code, message)

    override fun onLiveUnavailable(message: String) =
        callbacks().onLiveUnavailable(message)

    override fun onKickedOut() = callbacks().onKickedOut()

    override fun onLiveEnded() = callbacks().onLiveEnded()

    override fun onCurrentUserMessageDisabled(disabled: Boolean) =
        callbacks().onCurrentUserMessageDisabled(disabled)

    override fun onPictureInPictureChanged(enabled: Boolean) =
        callbacks().onPictureInPictureChanged(enabled)
}

/** 平台观看实现的中立命令面；创建、命令和释放由 UI 主线程串行调用。 */
interface LiveAudienceCommands {
    /** 同一观看实例的只读状态流；不包含 SDK 对象，不得跨房间复用命令实例。 */
    val snapshots: kotlinx.coroutines.flow.StateFlow<LiveAudienceContentSnapshot>
    /** 当前已确认快照，与 [snapshots] 的最新值一致。 */
    val snapshot: LiveAudienceContentSnapshot
    /**
     * 原始文本弹幕；完成回调在 Main 返回 SDK 成功标记与错误描述。
     *
     * @param message 原始弹幕文本，编码与文案由宿主决定。
     * @param onFinished Main 上返回 SDK 成功标记和错误描述。
     */
    fun sendBarrage(message: String, onFinished: (Boolean, String) -> Unit)
    /** 合并一次本地点赞；成功批次再交给宿主业务上报。 */
    fun like()
    /** 以当前已知状态设置关注目标；在途请求不重复发起。 */
    fun toggleFollow()
    /** 请求刷新在线观众；SDK 结果通过 [snapshots] 发布。 */
    fun refreshAudience()
    /**
     * 发起系统 PiP 请求；[wideContent] 选择横向画布，返回 true 只表示请求已接受。
     *
     * @param wideContent true 使用横向画布，false 使用竖向画布。
     */
    fun enterPictureInPicture(wideContent: Boolean): Boolean
    /**
     * 同步宿主回报的系统 PiP 状态，不发起新的进入请求。
     *
     * @param enabled 平台 PiP 标记，不能单独证明浮窗可见。
     */
    fun updatePictureInPicture(enabled: Boolean)
    /** 幂等切断当前实例的监听；真实离房完成或超时后才放行共享会话队列。 */
    fun release()
}
