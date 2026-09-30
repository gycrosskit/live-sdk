package io.github.gycrosskit.livesdk

/**
 * 观看控件事件；平台 actual 必须始终调用重组后的最新实例。
 *
 * @property onJoinStarted 获得共享会话执行权并开始调用原生进房。
 * @property onJoinSucceeded 原生 SDK 确认进房成功。
 * @property onJoinFailed 进房失败；参数为平台错误码和可展示描述。
 * @property onLiveUnavailable 直播不存在、已结束或当前账号无法观看。
 * @property onKickedOut 观看期间被主播或管理员移出当前直播间。
 * @property onLiveEnded 观看期间收到关播或解散事件。
 * @property onCurrentUserMessageDisabled 当前登录观众的弹幕权限变化。
 * @property onPictureInPictureChanged 系统画中画实际状态变化，不是请求结果的乐观回调。
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

/** 平台观看实现只通过该中立命令面与 common 状态连接。 */
interface LiveAudienceCommands {
    val snapshots: kotlinx.coroutines.flow.StateFlow<LiveAudienceContentSnapshot>
    val snapshot: LiveAudienceContentSnapshot
    fun sendBarrage(message: String, onFinished: (Boolean, String) -> Unit)
    fun like()
    fun toggleFollow()
    fun refreshAudience()
    /** 发起系统 PiP 请求，不得用 App 内悬浮层替代。 */
    fun enterPictureInPicture(wideContent: Boolean): Boolean
    fun updatePictureInPicture(enabled: Boolean)
    fun release()
}
