package io.github.gycrosskit.livesdk

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

internal fun kuiklyLiveCallbacks(emit: (String) -> Unit) = LivePlaybackCallbacks(
    onJoinStarted = { emit(liveEventJson("joinStarted")) },
    onJoinSucceeded = { emit(liveEventJson("joinSucceeded")) },
    onJoinFailed = { code, message -> emit(liveEventJson("joinFailed", code, message)) },
    onLiveUnavailable = { emit(liveEventJson("liveUnavailable", message = it)) },
    onKickedOut = { emit(liveEventJson("kickedOut")) },
    onLiveEnded = { emit(liveEventJson("liveEnded")) },
    onCurrentUserMessageDisabled = { emit(JSONObject().put("type", "messageDisabled").put("disabled", it).toString()) },
    onPictureInPictureChanged = { emit(JSONObject().put("type", "pictureInPictureChanged").put("enabled", it).toString()) },
)

internal fun liveEventJson(type: String, code: Int = 0, message: String = "") =
    JSONObject().put("type", type).put("code", code).put("message", message).toString()

internal data class KuiklyLiveRoom(val liveId: String, val preview: Boolean, val active: Boolean) {
    companion object {
        fun parse(value: String): KuiklyLiveRoom {
            val json = JSONObject(value)
            require(json.opt("liveId") is String &&
                (json.opt("preview") == null || json.opt("preview") is Boolean) &&
                (json.opt("active") == null || json.opt("active") is Boolean)) { "Invalid room configuration" }
            val liveId = json.optString("liveId")
            require(liveId.isNotBlank()) { "liveId must not be blank" }
            return KuiklyLiveRoom(liveId, json.optBoolean("preview"), json.optBoolean("active", true))
        }
    }
}
