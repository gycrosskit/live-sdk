package io.github.gycrosskit.livesdk

import android.content.Context
import android.view.View

/** 当前页面的 Main 观看实例，复用共用 Store/会话；不执行账号登录。
 *
 * @param context 当前页面 Context，只在实例存活期间持有。
 * @param liveId 当前实例固定的腾讯房间 ID。
 * @param callbacks Main 上交付的观看事件，宿主负责业务路由与提示。
 * @param likeReporter 已确认的点赞批次上报，不能承担同步阻塞任务。
 */
class AndroidLiveAudienceSession(
    context: Context,
    liveId: String,
    callbacks: LivePlaybackCallbacks,
    likeReporter: LiveLikeReporter = LiveLikeReporter.None,
) {
    private val player = AtomicAudienceView(context, liveId, LivePlaybackCallbackListener { callbacks }, likeReporter)
    /** 当前实例的原生视频容器，由宿主挂载；仅 Main 操作。 */
    val view: View get() = player.nativeView
    /** 与底层观看会话共用的中立状态流。 */
    val snapshots get() = player.snapshots
    /** 当前已确认观看快照。 */
    val snapshot: LiveAudienceContentSnapshot get() = player.snapshot
    /**
     * 发送原始弹幕文本，完成回调在 Main 返回结果和错误描述。
     *
     * @param message 原始弹幕文本，编码与文案由宿主决定。
     * @param callback 当前操作完成回执，Native 结果在 Main 交付。
     */
    fun sendBarrage(message: String, callback: (Boolean, String) -> Unit) = player.sendBarrage(message, callback)
    /** 将一次本地点赞加入当前会话的合并批次。 */
    fun like() = player.like()
    /** 切换当前主播关注目标；在途操作不会重复发送。 */
    fun toggleFollow() = player.toggleFollow()
    /** 刷新在线观众，结果通过 snapshots 发布。 */
    fun refreshAudience() = player.refreshAudience()
    /** 返回值仅表示系统接受请求；真实状态由 Activity 回报。 */
    fun enterPictureInPicture(wideContent: Boolean): Boolean = player.enterPictureInPicture(wideContent)
    /** 仅透传宿主 Activity 的真实 PiP 状态，不以请求成功替代浮窗状态。 */
    fun updatePictureInPicture(enabled: Boolean) = player.updatePictureInPicture(enabled)
    /** Main 幂等释放当前会话；此实例不能重新使用。 */
    fun release() = player.release()
}

/**
 * Main 创建的固定房间静音预览；宿主必须同步列表 active 与页面可见性。
 *
 * @param context 当前页面 Context，在实例存活期间持有。
 * @param liveId 当前实例的腾讯房间 ID，不跨房间复用。
 * @param onStateChanged Main 上交付预览状态变化。
 */
class AndroidLivePreviewSession(context: Context, liveId: String, onStateChanged: (LivePreviewState) -> Unit) {
    private val playback = AtomicLiveCorePreviewPlayback(context, liveId)
    private val controller = AtomicLivePreviewController(liveId, playback, onStateChanged, AtomicAudienceLivePreviewStartGate)
    /** 当前实例的原生视频容器，由宿主挂载；仅 Main 操作。 */
    val view: View get() = playback.container
    /**
     * 只有 active 与 visible 同时为 true 才播放；任一失效立即停止。
     *
     * @param active 业务激活标记，false 停止播放。
     * @param visible 宿主窗口或页面可见性，false 停止预览。
     */
    fun update(active: Boolean, visible: Boolean) {
        controller.setActive(active)
        controller.setLifecycleStarted(visible)
    }
    /** Main 幂等释放当前会话；此实例不能重新使用。 */
    fun release() = controller.release()
}
