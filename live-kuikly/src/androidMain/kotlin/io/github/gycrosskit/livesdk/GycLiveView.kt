package io.github.gycrosskit.livesdk

import android.content.Context
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.tencent.kuikly.core.render.android.export.IKuiklyRenderViewExport
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Native 视频层直接复用 CMP 的会话和 Store，不创建 ComposeView 或独立登录。 */
class GycLiveView(context: Context) : FrameLayout(context), IKuiklyRenderViewExport {
    private var room: KuiklyLiveRoom? = null
    private var event: KuiklyRenderCallback? = null
    private var audience: AndroidLiveAudienceSession? = null
    private var preview: AndroidLivePreviewSession? = null
    private var scope: CoroutineScope? = null
    private var snapshotJob: Job? = null
    private var lifecycle: Lifecycle? = null
    private var explicitRelease = false
    private var generation = 0L
    private val pendingEvents = ArrayDeque<String>()
    private val observer = LifecycleEventObserver { _, _ -> reconcile() }

    /** 业务点赞上报与 CMP 使用同一个宿主 reporter。 */
    var likeReporter: LiveLikeReporter = LiveLikeReporter.None

    override fun setProp(propKey: String, propValue: Any): Boolean = when (propKey) {
        "liveEvent" -> {
            event = propValue as KuiklyRenderCallback
            while (pendingEvents.isNotEmpty()) emit(pendingEvents.removeFirst())
            true
        }
        "room" -> {
            try {
                val next = KuiklyLiveRoom.parse(propValue as String)
                if (room?.liveId != next.liveId || room?.preview != next.preview) stop()
                room = next
                explicitRelease = false
                reconcile()
            } catch (_: Exception) { emit(liveEventJson("joinFailed", -1, "Invalid room configuration")) }
            true
        }
        else -> false
    }

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        when (method) {
            "release" -> { explicitRelease = true; stop() }
            "like" -> audience?.like()
            "toggleFollow" -> audience?.toggleFollow()
            "refreshAudience" -> audience?.refreshAudience()
            "sendBarrage" -> {
                val message = try { JSONObject(params ?: "{}").optString("message") } catch (_: Exception) { "" }
                val player = audience
                if (player == null || message.isBlank()) callback?.invoke(mapOf("success" to false, "message" to "Not ready"))
                else {
                    val token = generation
                    player.sendBarrage(message) { success, description ->
                        callback?.invoke(mapOf("success" to (success && token == generation), "message" to if (token == generation) description else "Released"))
                    }
                }
            }
        }
        return null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lifecycle = findViewTreeLifecycleOwner()?.lifecycle
        lifecycle?.addObserver(observer)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope?.launch { AndroidLiveSdkRuntime.sessionReadyFlow.collect { reconcile() } }
        reconcile()
    }

    override fun onDetachedFromWindow() {
        lifecycle?.removeObserver(observer)
        lifecycle = null
        stop()
        scope?.cancel()
        scope = null
        super.onDetachedFromWindow()
    }

    private fun reconcile() {
        val request = room ?: return
        if (!isAttachedToWindow || explicitRelease) return
        if (!AndroidLiveSdkRuntime.isSessionReady) { stop(); return }
        if (request.preview) {
            if (preview == null) {
                val player = AndroidLivePreviewSession(context, request.liveId) { state ->
                    emit(JSONObject().put("type", "previewState").put("state", state.name).toString())
                }
                preview = player
                addView(player.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
            // 无 LifecycleOwner 时保持 COVER，宿主必须提供正常页面生命周期。
            preview?.update(request.active, lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true)
        } else if (audience == null && request.active) {
            val token = ++generation
            val player = AndroidLiveAudienceSession(context, request.liveId, kuiklyLiveCallbacks { if (token == generation) emit(it) }, likeReporter)
            audience = player
            addView(player.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            snapshotJob = scope?.launch { player.snapshots.collect { snapshot ->
                emit(JSONObject().put("type", "snapshot").put("snapshot", JSONObject(snapshot.toKuiklyJson())).toString())
            } }
        } else if (!request.active) stop()
    }

    private fun stop() {
        generation++
        pendingEvents.clear()
        snapshotJob?.cancel(); snapshotJob = null
        preview?.release(); preview = null
        audience?.release(); audience = null
        removeAllViews()
    }

    private fun emit(value: String) {
        val callback = event
        if (callback != null) callback.invoke(org.json.JSONObject(value).toMap())
        else {
            if (pendingEvents.size == 16) pendingEvents.removeFirst()
            pendingEvents.addLast(value)
        }
    }
}

private fun org.json.JSONObject.toMap(): Map<String, Any> = keys().asSequence().associateWith { key ->
    when (val value = get(key)) {
        is org.json.JSONObject -> value.toMap()
        is org.json.JSONArray -> (0 until value.length()).map { index ->
            val item = value.get(index)
            if (item is org.json.JSONObject) item.toMap() else item
        }
        else -> value
    }
}
