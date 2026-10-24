@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package io.github.gycrosskit.livesdk

import com.tencent.kuikly.compose.coroutines.internal.KuiklyContextScheduler
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Renderer 只传输；公共观看状态仍在 Main，ArkTS 只持有原生 Store/View。 */
internal class KuiklyNativeLiveController(
    private val pagerId: String,
    private val likeReporter: LiveLikeReporter,
    private val call: (String, JSONObject, ((JSONObject) -> Unit)?) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var room: KuiklyLiveRoom? = null
    private var ready = false
    private var visible = false
    private var explicitRelease = false
    private var disposed = false
    private var generation = 0L
    private var audience: LiveBridgeAudienceSession? = null
    private var observer: LiveAudiencePlatformObserver? = null
    private var snapshots: Job? = null
    private var preview: AtomicLivePreviewController? = null
    private var previewCallback: AtomicLivePreviewPlayback.Callback? = null

    fun event(event: JSONObject) { scope.launch { receive(event) } }

    private fun receive(event: JSONObject) {
        if (disposed) return
        when (event.optString("type")) {
            "configuration" -> {
                try {
                    val next = KuiklyLiveRoom.parse(event.optString("room"))
                    if (room?.liveId != next.liveId || room?.preview != next.preview) stop()
                    room = next
                    explicitRelease = false
                } catch (_: Exception) {
                    room = null
                    stop()
                    publish(JSONObject(liveEventJson("joinFailed", -1, "Invalid room configuration")))
                    return
                }
                ready = event.optBoolean("ready")
                visible = event.optBoolean("visible")
                reconcile()
            }
            "visibility" -> { visible = event.optBoolean("visible"); ready = event.optBoolean("ready"); reconcile() }
            "destroyed" -> release()
            "command" -> command(event)
            else -> {
                if (event.optLong("generation") != generation) return
                when (event.optString("type")) {
                    "pictureInPictureChanged" -> observer?.pictureInPictureChanged(event.optBoolean("enabled"))
                    "ended" -> observer?.liveEnded()
                    "kickedOut" -> observer?.kickedOut()
                    "playbackError" -> publish(JSONObject(liveEventJson("playbackError", event.optInt("code", -1), event.optString("message"))))
                    "messages" -> {
                        val list = event.optJSONArray("messages") ?: return
                        observer?.messages((0 until list.length()).mapNotNull { index ->
                            val item = list.optJSONObject(index) ?: return@mapNotNull null
                            LiveAudienceMessageSnapshot(item.optLong("sequence"), item.optDouble("timestampSeconds"),
                                item.optString("senderId"), item.optString("senderName"), item.optString("senderAvatarUrl"),
                                item.optString("content"), item.optString("businessId"), item.optString("data"))
                        })
                    }
                    "memberChanged" -> {
                        val user = event.optJSONObject("user") ?: return
                        observer?.memberChanged(
                            LiveAudienceUserSnapshot(user.optString("id"), user.optString("name"), user.optString("avatarUrl")),
                            event.optBoolean("joined"), event.optDouble("timestampSeconds"),
                        )
                    }
                    "audience" -> {
                        val list = event.optJSONArray("audience") ?: return
                        observer?.audience((0 until list.length()).mapNotNull { index ->
                            val item = list.optJSONObject(index) ?: return@mapNotNull null
                            LiveAudienceUserSnapshot(item.optString("id"), item.optString("name"), item.optString("avatarUrl"))
                        }, event.optInt("count"))
                    }
                    "likes" -> observer?.likes(event.optInt("count"))
                    "messageDisabled" -> observer?.messageDisabled(event.optBoolean("disabled"))
                    "previewState" -> when (event.optString("state")) {
                        "PLAYING" -> previewCallback?.onPlaying()
                        "FAILED" -> previewCallback?.onFailure(event.optInt("code", -1), event.optString("message"))
                        else -> previewCallback?.onLoading()
                    }
                }
            }
        }
    }

    private fun reconcile() {
        val request = room ?: return
        if (disposed || explicitRelease) return
        if (!ready || !request.active || !visible) { stop(); return }
        if (request.preview) {
            if (preview == null) {
                val token = ++generation
                preview = AtomicLivePreviewController(request.liveId, object : AtomicLivePreviewPlayback {
                    override fun start(liveId: String, callback: AtomicLivePreviewPlayback.Callback) {
                        previewCallback = callback
                        native("sdkPreview", token, { put("liveId", liveId) })
                    }
                    override fun stopAndDetach(liveId: String) {
                        previewCallback = null
                        native("sdkStopPreview", token)
                    }
                }, { state -> publish(JSONObject().put("type", "previewState").put("state", state.name)) }, AtomicAudienceLivePreviewStartGate)
            }
            preview?.setActive(true)
            preview?.setLifecycleStarted(true)
        } else if (audience == null) {
            val token = ++generation
            val session = LiveBridgeAudienceSession(request.liveId, object : LiveAudiencePlatform {
                override var currentUserId: String = ""
                override fun join(liveId: String, observer: LiveAudiencePlatformObserver) {
                    this@KuiklyNativeLiveController.observer = observer
                    native("sdkJoin", token, { put("liveId", liveId) }) { result ->
                        // join 回调仍须交公共状态机结算；released 的旧实例不能更新当前快照。
                        if (result.optBoolean("success")) {
                            currentUserId = result.optString("userId")
                            val host = LiveAudienceHostSnapshot(liveId, result.optString("liveName"),
                                LiveAudienceUserSnapshot(result.optString("ownerId"), result.optString("ownerName"), result.optString("ownerAvatarUrl")),
                                false, false, false, 0L)
                            observer.joined(host, result.optString("notice").ifBlank { host.roomName })
                            if (token == generation) native("sdkObserve", token)
                        } else observer.joinFailed(result.optInt("code", -1), result.optString("message"))
                    }
                }
                override fun leave(onFinished: (Boolean, Int, String) -> Unit) {
                    native("sdkLeave", token, callback = { onFinished(it.optBoolean("success"), it.optInt("code", -1), it.optString("message")) })
                }
                override fun sendBarrage(message: String, onFinished: (Boolean, String) -> Unit) {
                    native("sdkSendBarrage", token, { put("message", message) }) { onFinished(it.optBoolean("success"), it.optString("message")) }
                }
                override fun sendLike(count: Int, onFinished: (Boolean) -> Unit) {
                    native("sdkLike", token, { put("count", count) }) { onFinished(it.optBoolean("success")) }
                }
                override fun refreshAudience() { native("sdkObserve", token) }
                override fun checkFollowed(userId: String, callback: (LiveFollowResult<Boolean>) -> Unit) {
                    native("sdkCheckFollow", token, { put("userId", userId) }) {
                        callback(if (it.optBoolean("success")) LiveFollowResult.Success(it.optBoolean("followed")) else LiveFollowResult.Failure(it.optString("message")))
                    }
                }
                override fun updateFollow(userId: String, followed: Boolean, callback: (LiveFollowResult<Unit>) -> Unit) {
                    native("sdkFollow", token, { put("userId", userId); put("followed", followed) }) {
                        callback(if (it.optBoolean("success")) LiveFollowResult.Success(Unit) else LiveFollowResult.Failure(it.optString("message")))
                    }
                }
                override fun fetchFans(userId: String, callback: (LiveFollowResult<Long>) -> Unit) {
                    native("sdkFans", token, { put("userId", userId) }) {
                        callback(if (it.optBoolean("success")) LiveFollowResult.Success(it.optLong("fansCount")) else LiveFollowResult.Failure(it.optString("message")))
                    }
                }
            }, kuiklyLiveCallbacks { if (token == generation) publish(JSONObject(it)) }, likeReporter)
            audience = session
            snapshots = scope.launch { session.snapshots.collect {
                if (token == generation) publish(JSONObject().put("type", "snapshot").put("snapshot", JSONObject(it.toKuiklyJson())))
            } }
        }
    }

    private fun command(event: JSONObject) {
        val id = event.optString("requestId")
        fun complete(success: Boolean, message: String = "") = native("complete", generation, {
            put("requestId", id); put("success", success); put("accepted", false); put("message", message)
        })
        when (event.optString("method")) {
            "release" -> { explicitRelease = true; stop() }
            "like" -> audience?.like()
            "refreshAudience" -> audience?.refreshAudience()
            "sendBarrage" -> audience?.sendBarrage(event.optString("message")) { ok, message -> complete(ok, message) } ?: complete(false, "Not ready")
            "toggleFollow" -> audience?.toggleFollow()
            "enterPictureInPicture" -> complete(false, "Picture in picture requires a native video node")
        }
    }

    private fun stop() {
        generation++
        snapshots?.cancel(); snapshots = null
        preview?.release(); preview = null
        audience?.release(); audience = null
        observer = null
    }

    fun release() { scope.launch {
        if (!disposed) { disposed = true; stop(); scope.cancel() }
    } }

    private fun publish(event: JSONObject) = native("publish", generation, { put("event", event) })

    private fun native(method: String, token: Long, configure: JSONObject.() -> Unit = {}, callback: ((JSONObject) -> Unit)? = null) {
        val params = JSONObject().put("generation", token).apply(configure)
        // shortcut: Kuikly 2.28 的 Renderer scheduler 为 internal；升级时核对线程与取消语义。
        KuiklyContextScheduler.runOnKuiklyThread(pagerId) { cancelled ->
            if (cancelled) {
                callback?.let { done -> CoroutineScope(Dispatchers.Main.immediate).launch { done(JSONObject().put("success", false).put("message", "Page released")) } }
            } else try {
                call(method, params, callback?.let { done -> { result ->
                    // 离房回执在页面销毁后仍须结算公共会话令牌。
                    CoroutineScope(Dispatchers.Main.immediate).launch { done(result) }
                    Unit
                } })
            } catch (_: Exception) {
                callback?.let { done -> CoroutineScope(Dispatchers.Main.immediate).launch {
                    done(JSONObject().put("success", false).put("message", "Native live call failed"))
                } }
            }
        }
    }
}
