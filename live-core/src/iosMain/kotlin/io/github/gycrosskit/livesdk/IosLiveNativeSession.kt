@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.gycrosskit.livesdk

import platform.UIKit.UIView

/** 原生 Kuikly/UI 宿主仍安装现有 IosLiveSdkBridge；本类不再次登录腾讯账号。 */
class IosLiveAudienceSession(
    liveId: String,
    callbacks: LivePlaybackCallbacks,
    likeReporter: LiveLikeReporter = LiveLikeReporter.None,
) {
    private val bridge = requireNotNull(IosLiveSdkRuntime.bridge) { "iOS live bridge is not installed" }
    private val player = IosAtomicAudienceView(bridge, liveId, LivePlaybackCallbackListener { callbacks }, likeReporter)
    val view = UIView().also(player::mount)
    val snapshots get() = player.snapshots
    val snapshot: LiveAudienceContentSnapshot get() = player.snapshot
    fun sendBarrage(message: String, callback: (Boolean, String) -> Unit) = player.sendBarrage(message, callback)
    fun like() = player.like()
    fun toggleFollow() = player.toggleFollow()
    fun refreshAudience() = player.refreshAudience()
    fun release() = player.release()
}

class IosLivePreviewSession(liveId: String, onStateChanged: (LivePreviewState) -> Unit) {
    val view = UIView()
    private val bridge = requireNotNull(IosLiveSdkRuntime.bridge) { "iOS live bridge is not installed" }
    private val playback = IosAtomicLivePreviewPlayback(bridge) { native ->
        if (native != null) {
            native.setFrame(view.bounds)
            native.autoresizingMask = platform.UIKit.UIViewAutoresizingFlexibleWidth or
                platform.UIKit.UIViewAutoresizingFlexibleHeight
            view.addSubview(native)
        }
    }
    private val controller = AtomicLivePreviewController(liveId, playback, onStateChanged, AtomicAudienceLivePreviewStartGate)
    fun update(active: Boolean, visible: Boolean) {
        controller.setActive(active)
        controller.setLifecycleStarted(visible)
    }
    fun release() = controller.release()
}
