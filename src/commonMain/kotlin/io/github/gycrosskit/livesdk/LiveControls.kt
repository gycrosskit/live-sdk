package io.github.gycrosskit.livesdk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

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
internal class LivePlaybackCallbackListener(
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

/** commonMain 持有中立命令转发，不公开原生播放器、AtomicX Store 或 Swift Bridge。 */
@Stable
class LivePlaybackState internal constructor() {
    private var commands: LiveAudienceCommands by mutableStateOf(EmptyLiveAudienceCommands)

    /** 当前原生观看快照；控件尚未安装时返回 [LiveAudienceContentSnapshot.Empty]。 */
    val snapshot: LiveAudienceContentSnapshot
        get() = commands.snapshot

    /** 发送文本弹幕；[onFinished] 回报成功状态与平台错误描述。 */
    fun sendBarrage(message: String, onFinished: (Boolean, String) -> Unit) =
        commands.sendBarrage(message, onFinished)

    /** 记录一次本地点赞，平台实现可批量合并上报。 */
    fun like() = commands.like()

    /** 按当前关注状态发起关注或取消关注。 */
    fun toggleFollow() = commands.toggleFollow()

    /** 请求平台刷新在线观众快照。 */
    fun refreshAudience() = commands.refreshAudience()

    /** 返回值只表示平台接受了请求；真实进入/退出结果由 [LivePlaybackCallbacks.onPictureInPictureChanged] 回报。 */
    fun enterPictureInPicture(wideContent: Boolean): Boolean =
        commands.enterPictureInPicture(wideContent)

    /** 同步宿主 Activity/UIViewController 回报的系统画中画状态。 */
    fun updatePictureInPicture(enabled: Boolean) = commands.updatePictureInPicture(enabled)

    /** 幂等释放当前观看会话。 */
    fun release() = commands.release()

    internal fun attach(commands: LiveAudienceCommands) {
        if (this.commands === commands) return
        this.commands.release()
        this.commands = commands
    }

    internal fun detach(commands: LiveAudienceCommands) {
        if (this.commands !== commands) return
        this.commands = EmptyLiveAudienceCommands
    }
}

/** 平台观看实现只通过该中立命令面与 common 状态连接。 */
internal interface LiveAudienceCommands {
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

private object EmptyLiveAudienceCommands : LiveAudienceCommands {
    override val snapshot = LiveAudienceContentSnapshot.Empty

    override fun sendBarrage(message: String, onFinished: (Boolean, String) -> Unit) {
        onFinished(false, "")
    }

    override fun like() = Unit
    override fun toggleFollow() = Unit
    override fun refreshAudience() = Unit
    override fun enterPictureInPicture(wideContent: Boolean) = false
    override fun updatePictureInPicture(enabled: Boolean) = Unit
    override fun release() = Unit
}

/** 创建并记住仅属于当前直播画面组合位置的命令状态。 */
@Composable
fun rememberLivePlaybackState(): LivePlaybackState = remember { LivePlaybackState() }

/** 列表静音预览控件；Android/iOS 原生差异完全收口到 [PlatformLiveCoreView]。 */
@Composable
fun LivePreview(
    liveId: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onStateChanged: (LivePreviewState) -> Unit = {},
) {
    PlatformLiveCoreView(
        request = LiveCoreViewRequest.Preview(liveId, active, onStateChanged),
        modifier = modifier,
    )
}

/**
 * 完整观看会话的底层原生视频画面。
 *
 * 本控件不是“直播间 UI”；弹幕、主播资料、观众、输入、点赞和 PiP 入口由 shared CMP
 * 围绕该画面统一组合。
 */
@Composable
fun LiveCoreView(
    liveId: String,
    modifier: Modifier = Modifier,
    state: LivePlaybackState = rememberLivePlaybackState(),
    callbacks: LivePlaybackCallbacks = LivePlaybackCallbacks(),
    likeReporter: LiveLikeReporter = LiveLikeReporter.None,
) {
    PlatformLiveCoreView(
        request = LiveCoreViewRequest.Playback(liveId, state, callbacks, likeReporter),
        modifier = modifier,
    )
}

/** 两种业务场景共用同一个平台 LiveCoreView 边界，避免为每种模式重复一套 expect/actual。 */
internal sealed interface LiveCoreViewRequest {
    data class Preview(
        val liveId: String,
        val active: Boolean,
        val onStateChanged: (LivePreviewState) -> Unit,
    ) : LiveCoreViewRequest

    data class Playback(
        val liveId: String,
        val state: LivePlaybackState,
        val callbacks: LivePlaybackCallbacks,
        val likeReporter: LiveLikeReporter,
    ) : LiveCoreViewRequest
}

@Composable
internal expect fun PlatformLiveCoreView(
    request: LiveCoreViewRequest,
    modifier: Modifier,
)
