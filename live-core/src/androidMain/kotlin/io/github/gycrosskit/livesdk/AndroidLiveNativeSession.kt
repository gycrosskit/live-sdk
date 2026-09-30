package io.github.gycrosskit.livesdk

import android.content.Context
import android.view.View

/** CMP 与其他原生渲染框架共用同一 Store/会话；不负责账号登录。 */
class AndroidLiveAudienceSession(
    context: Context,
    liveId: String,
    callbacks: LivePlaybackCallbacks,
    likeReporter: LiveLikeReporter = LiveLikeReporter.None,
) {
    private val player = AtomicAudienceView(context, liveId, LivePlaybackCallbackListener { callbacks }, likeReporter)
    val view: View get() = player.nativeView
    val snapshots get() = player.snapshots
    val snapshot: LiveAudienceContentSnapshot get() = player.snapshot
    fun sendBarrage(message: String, callback: (Boolean, String) -> Unit) = player.sendBarrage(message, callback)
    fun like() = player.like()
    fun toggleFollow() = player.toggleFollow()
    fun refreshAudience() = player.refreshAudience()
    fun release() = player.release()
}

class AndroidLivePreviewSession(context: Context, liveId: String, onStateChanged: (LivePreviewState) -> Unit) {
    private val playback = AtomicLiveCorePreviewPlayback(context, liveId)
    private val controller = AtomicLivePreviewController(liveId, playback, onStateChanged, AtomicAudienceLivePreviewStartGate)
    val view: View get() = playback.container
    fun update(active: Boolean, visible: Boolean) {
        controller.setActive(active)
        controller.setLifecycleStarted(visible)
    }
    fun release() = controller.release()
}
