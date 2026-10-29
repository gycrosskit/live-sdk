package io.github.gycrosskit.livesdk

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 只替换跨 Renderer 的节点边界；Compose 状态、Effect、JSON 与被测 Composable 均为真实实现。 */
class KuiklyLiveView {
    var likeReporter: LiveLikeReporter = LiveLikeReporter.None
    val rooms = mutableListOf<JSONObject>()
    var releaseCount = 0
    var listener: ((JSONObject) -> Unit)? = null
    val reporterAtSubmission = mutableListOf<LiveLikeReporter>()
    fun getViewEvent() = Events()
    fun getViewAttr() = Attr()
    inner class Events { fun liveEvent(block: (JSONObject) -> Unit) { listener = block } }
    inner class Attr {
        fun setProp(name: String, value: String) {
            check(name == "room")
            rooms += JSONObject(value)
            reporterAtSubmission += likeReporter
        }
    }
    fun release() { releaseCount++ }
    fun sendBarrage(message: String, callback: (Boolean, String) -> Unit) = callback(true, "")
    fun like() {}
    fun toggleFollow() {}
    fun refreshAudience() {}
    fun enterPictureInPicture(wideContent: Boolean, callback: (Boolean) -> Unit) = callback(true)
    fun updatePictureInPicture(enabled: Boolean) {}
}
