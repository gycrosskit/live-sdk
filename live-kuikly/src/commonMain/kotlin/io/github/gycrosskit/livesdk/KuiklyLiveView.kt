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

    /**
     * 待原生节点就绪后发送弹幕，callback 返回已确认的 SDK 结果。
     *
     * @param message 原始弹幕文本，编码与文案由宿主决定。
     * @param callback Renderer 回报成功标记与描述；节点未加载时任务等待渲染。
     */
    fun sendBarrage(message: String, callback: (Boolean, String) -> Unit) {
        performTaskWhenRenderViewDidLoad {
            renderView?.callMethod("sendBarrage", JSONObject().put("message", message).toString()) {
                val result = it as? JSONObject
                callback(result?.optBoolean("success") == true, result?.optString("message").orEmpty())
            }
        }
    }

    /** 原生节点就绪后记录一次当前观看会话点赞。 */
    fun like() = command("like")
    /** 切换主播关注目标，采用原生共用状态机的在途门禁。 */
    fun toggleFollow() = command("toggleFollow")
    /** 请求刷新当前观看会话的在线观众快照。 */
    fun refreshAudience() = command("refreshAudience")
    /** 异步回报平台是否接受请求；accepted 不能证明系统浮窗可见。 */
    fun enterPictureInPicture(wideContent: Boolean, callback: (accepted: Boolean) -> Unit) {
        performTaskWhenRenderViewDidLoad {
            renderView?.callMethod("enterPictureInPicture", JSONObject().put("wideContent", wideContent).toString()) {
                callback((it as? JSONObject)?.optBoolean("accepted") == true)
            }
        }
    }
    /** 同步宿主收到的真实平台 PiP 状态；不要用 enter 的 accepted 回执填入。 */
    fun updatePictureInPicture(enabled: Boolean) {
        performTaskWhenRenderViewDidLoad {
            renderView?.callMethod("updatePictureInPicture", JSONObject().put("enabled", enabled).toString())
        }
    }
    /** 退出当前会话，直到重新下发 room 才可重启；原生节点销毁时永久释放。 */
    fun release() = command("release")

    private fun command(name: String) {
        performTaskWhenRenderViewDidLoad { renderView?.callMethod(name, null) }
    }

    companion object {
        /** Native View 注册名称，双端宿主必须使用相同名称。 */
        const val VIEW_NAME = "GycLiveView"
    }
}

/** 一次下发房间、预览模式及活动标记的原生属性。 */
class KuiklyLiveAttr : Attr() {
    /**
     * 一次提交完整配置，避免 native 因属性更新顺序创建错误模式的会话。
     *
     * @param liveId 当前实例的腾讯房间 ID，不跨房间复用。
     * @param preview true 静音预览，默认 false 完整观看。
     * @param active 业务激活标记，false 停止播放。
     */
    fun room(liveId: String, preview: Boolean = false, active: Boolean = true): KuiklyLiveAttr {
        "room" with JSONObject().put("liveId", liveId).put("preview", preview).put("active", active).toString()
        return this
    }
}

/** 原生 SDK 确认后的会话事件与中立快照，不代替宿主业务路由。 */
class KuiklyLiveEvent : Event() {
    /**
     * type 对应 SDK 已确认的事件；snapshot 携带现有中立快照的 JSON。
     *
     * @param handler Renderer 接收原生事件 JSON 的处理器。
     */
    fun liveEvent(handler: (JSONObject) -> Unit) {
        register("liveEvent") { handler(it as? JSONObject ?: JSONObject()) }
    }
}

/**
 * 在 Kuikly 容器中添加视频节点；尺寸与业务操作层由宿主声明。
 *
 * @param init 视频节点属性、事件和尺寸配置。
 */
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
