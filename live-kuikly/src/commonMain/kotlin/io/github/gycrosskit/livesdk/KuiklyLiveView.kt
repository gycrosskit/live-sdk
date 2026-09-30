package io.github.gycrosskit.livesdk

import com.tencent.kuikly.core.base.Attr
import com.tencent.kuikly.core.base.DeclarativeBaseView
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.event.Event
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 视频层；账号、业务接口、房间页面和生命周期策略由宿主提供。 */
class KuiklyLiveView : DeclarativeBaseView<KuiklyLiveAttr, KuiklyLiveEvent>() {
    override fun viewName() = VIEW_NAME
    override fun createAttr() = KuiklyLiveAttr()
    override fun createEvent() = KuiklyLiveEvent()

    fun sendBarrage(message: String, callback: (Boolean, String) -> Unit) {
        performTaskWhenRenderViewDidLoad {
            renderView?.callMethod("sendBarrage", JSONObject().put("message", message).toString()) {
                val result = it as? JSONObject
                callback(result?.optBoolean("success") == true, result?.optString("message").orEmpty())
            }
        }
    }

    fun like() = command("like")
    fun toggleFollow() = command("toggleFollow")
    fun refreshAudience() = command("refreshAudience")
    fun release() = command("release")

    private fun command(name: String) {
        performTaskWhenRenderViewDidLoad { renderView?.callMethod(name, null) }
    }

    companion object { const val VIEW_NAME = "GycLiveView" }
}

class KuiklyLiveAttr : Attr() {
    /** 一次提交完整配置，避免 native 因属性更新顺序创建错误模式的会话。 */
    fun room(liveId: String, preview: Boolean = false, active: Boolean = true): KuiklyLiveAttr {
        "room" with JSONObject().put("liveId", liveId).put("preview", preview).put("active", active).toString()
        return this
    }
}

class KuiklyLiveEvent : Event() {
    /** type 对应 SDK 已确认的事件；snapshot 携带现有中立快照的 JSON。 */
    fun liveEvent(handler: (JSONObject) -> Unit) {
        register("liveEvent") { handler(it as? JSONObject ?: JSONObject()) }
    }
}

fun ViewContainer<*, *>.LiveVideo(init: KuiklyLiveView.() -> Unit) = addChild(KuiklyLiveView(), init)

/** Native 两端共用事件编码，不引入第二份观看状态。 */
internal fun LiveAudienceContentSnapshot.toKuiklyJson(): String = JSONObject().apply {
    put("introduction", introduction)
    put("interactionReady", interactionReady)
    put("loading", loading)
    put("pictureInPicture", pictureInPicture)
    put("likeEffectSequence", likeEffectSequence)
    put("audienceCount", audienceCount)
    put("host", JSONObject().apply {
        put("roomId", host.roomId); put("roomName", host.roomName)
        put("followVisible", host.followVisible); put("followed", host.followed)
        put("followRequestRunning", host.followRequestRunning); put("fansCount", host.fansCount)
        host.owner?.let { put("owner", it.toKuiklyJson()) }
    })
    put("audience", com.tencent.kuikly.core.nvi.serialization.json.JSONArray().apply {
        audience.forEach { put(it.toKuiklyJson()) }
    })
    put("messages", com.tencent.kuikly.core.nvi.serialization.json.JSONArray().apply {
        messages.forEach { item -> put(JSONObject().apply {
            put("sequence", item.sequence); put("timestampSeconds", item.timestampSeconds)
            put("senderId", item.senderId); put("senderName", item.senderName)
            put("senderAvatarUrl", item.senderAvatarUrl); put("content", item.content)
            put("businessId", item.businessId); put("data", item.data); put("kind", item.kind.name)
        }) }
    })
}.toString()

private fun LiveAudienceUserSnapshot.toKuiklyJson() = JSONObject()
    .put("id", id).put("name", name).put("avatarUrl", avatarUrl)
