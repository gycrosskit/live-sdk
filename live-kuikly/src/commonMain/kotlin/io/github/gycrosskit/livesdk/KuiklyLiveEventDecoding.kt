package io.github.gycrosskit.livesdk

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 只读取 snapshot 信封；成员消息保留语义与原始内容，由宿主按当前语言展示。 */
fun JSONObject.toLiveAudienceSnapshot(): LiveAudienceContentSnapshot? {
    if (optString("type") != "snapshot") return null
    val snapshot = opt("snapshot") as? JSONObject ?: return null
    val host = snapshot.opt("host") as? JSONObject ?: JSONObject()
    val messages = snapshot.opt("messages") as? JSONArray
    val audience = snapshot.opt("audience") as? JSONArray
    return LiveAudienceContentSnapshot(
        messages = (0 until (messages?.length() ?: 0)).mapNotNull { index ->
            val item = messages?.opt(index) as? JSONObject ?: return@mapNotNull null
            LiveAudienceMessageSnapshot(
                sequence = item.optLong("sequence"),
                timestampSeconds = item.optDouble("timestampSeconds"),
                senderId = item.optString("senderId"),
                senderName = item.optString("senderName"),
                senderAvatarUrl = item.optString("senderAvatarUrl"),
                content = item.optString("content"),
                businessId = item.optString("businessId"),
                data = item.optString("data"),
                kind = when (item.optString("kind")) {
                    "MEMBER_JOINED" -> LiveAudienceMessageKind.MEMBER_JOINED
                    "MEMBER_LEFT" -> LiveAudienceMessageKind.MEMBER_LEFT
                    else -> LiveAudienceMessageKind.TEXT
                },
            )
        },
        introduction = snapshot.optString("introduction"),
        interactionReady = snapshot.optBoolean("interactionReady"),
        loading = snapshot.optBoolean("loading", true),
        pictureInPicture = snapshot.optBoolean("pictureInPicture"),
        likeEffectSequence = snapshot.optLong("likeEffectSequence"),
        host = LiveAudienceHostSnapshot(
            roomId = host.optString("roomId"),
            roomName = host.optString("roomName"),
            owner = (host.opt("owner") as? JSONObject)?.toLiveAudienceUser(),
            followVisible = host.optBoolean("followVisible"),
            followed = host.optBoolean("followed"),
            followRequestRunning = host.optBoolean("followRequestRunning"),
            fansCount = host.optLong("fansCount"),
        ),
        audience = (0 until (audience?.length() ?: 0)).mapNotNull { index ->
            (audience?.opt(index) as? JSONObject)?.toLiveAudienceUser()
        },
        audienceCount = snapshot.optInt("audienceCount"),
    )
}

private fun JSONObject.toLiveAudienceUser() =
    LiveAudienceUserSnapshot(optString("id"), optString("name"), optString("avatarUrl"))

/** 返回是否识别该播放事件；技术播放错误独立交付，不替宿主终止房间。回调异常直接传递给调用方。 */
fun JSONObject.dispatchLivePlaybackEvent(
    callbacks: LivePlaybackCallbacks,
    onPlaybackError: (Int, String) -> Unit = { _, _ -> },
): Boolean {
    when (optString("type")) {
        "joinStarted" -> callbacks.onJoinStarted()
        "joinSucceeded" -> callbacks.onJoinSucceeded()
        "joinFailed" -> callbacks.onJoinFailed(optInt("code"), optString("message"))
        "liveUnavailable" -> callbacks.onLiveUnavailable(optString("message"))
        "kickedOut" -> callbacks.onKickedOut()
        "liveEnded" -> callbacks.onLiveEnded()
        "messageDisabled" -> callbacks.onCurrentUserMessageDisabled(optBoolean("disabled"))
        "pictureInPictureChanged" -> callbacks.onPictureInPictureChanged(optBoolean("enabled"))
        "playbackError" -> onPlaybackError(optInt("code", -1), optString("message"))
        else -> return false
    }
    return true
}
