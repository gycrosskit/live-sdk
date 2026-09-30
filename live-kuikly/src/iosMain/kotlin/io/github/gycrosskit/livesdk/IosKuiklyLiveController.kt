@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.gycrosskit.livesdk

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.UIKit.UIView

/** Swift KuiklyView 的薄控制器；观看会话仍由 live-core 和宿主 IosLiveSdkBridge 持有。 */
class IosKuiklyLiveController(private val onEvent: (String) -> Unit) {
    val view = UIView()
    var likeReporter: LiveLikeReporter = LiveLikeReporter.None
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var room: KuiklyLiveRoom? = null
    private var audience: IosLiveAudienceSession? = null
    private var preview: IosLivePreviewSession? = null
    private var snapshots: Job? = null
    private var visible = false
    private var released = false
    private var generation = 0L

    init { scope.launch { IosLiveSdkRuntime.sessionReadyFlow.collect { reconcile() } } }

    fun configure(value: String) {
        if (released) return
        try {
            val next = KuiklyLiveRoom.parse(value)
            if (room?.liveId != next.liveId || room?.preview != next.preview) stop()
            room = next
            reconcile()
        } catch (_: Exception) { onEvent(liveEventJson("joinFailed", -1, "Invalid room configuration")) }
    }

    fun setVisible(value: Boolean) {
        visible = value
        reconcile()
    }

    fun command(method: String, params: String, callback: (String) -> Unit) {
        when (method) {
            "like" -> audience?.like()
            "toggleFollow" -> audience?.toggleFollow()
            "refreshAudience" -> audience?.refreshAudience()
            "release" -> release()
            "sendBarrage" -> {
                val message = try { JSONObject(params).optString("message") } catch (_: Exception) { "" }
                val player = audience
                if (player == null || message.isBlank()) callback(JSONObject().put("success", false).put("message", "Not ready").toString())
                else {
                    val token = generation
                    player.sendBarrage(message) { success, description ->
                        callback(JSONObject().put("success", success && token == generation)
                            .put("message", if (token == generation) description else "Released").toString())
                    }
                }
            }
        }
    }

    fun release() {
        if (released) return
        released = true
        stop()
        scope.cancel()
    }

    private fun reconcile() {
        if (released) return
        val request = room ?: return
        if (!IosLiveSdkRuntime.isSessionReady) { stop(); return }
        if (request.preview) {
            if (preview == null && visible) {
                try {
                    val player = IosLivePreviewSession(request.liveId) { state ->
                        onEvent(JSONObject().put("type", "previewState").put("state", state.name).toString())
                    }
                    preview = player
                    mount(player.view)
                } catch (_: Exception) { onEvent(liveEventJson("joinFailed", -1, "iOS live bridge is not installed")) }
            }
            preview?.update(request.active, visible)
        } else if (audience == null && request.active && visible) {
            try {
                val token = ++generation
                val player = IosLiveAudienceSession(request.liveId, kuiklyLiveCallbacks { if (!released && token == generation) onEvent(it) }, likeReporter)
                audience = player
                mount(player.view)
                snapshots = scope.launch { player.snapshots.collect { snapshot ->
                    onEvent(JSONObject().put("type", "snapshot").put("snapshot", JSONObject(snapshot.toKuiklyJson())).toString())
                } }
            } catch (_: Exception) { onEvent(liveEventJson("joinFailed", -1, "iOS live bridge is not installed")) }
        } else if (!request.active) stop()
    }

    private fun mount(child: UIView) {
        child.setFrame(view.bounds)
        child.autoresizingMask = platform.UIKit.UIViewAutoresizingFlexibleWidth or platform.UIKit.UIViewAutoresizingFlexibleHeight
        view.addSubview(child)
    }

    private fun stop() {
        generation++
        snapshots?.cancel(); snapshots = null
        preview?.release(); preview = null
        audience?.release(); audience = null
        view.subviews.filterIsInstance<UIView>().forEach { it.removeFromSuperview() }
    }
}
