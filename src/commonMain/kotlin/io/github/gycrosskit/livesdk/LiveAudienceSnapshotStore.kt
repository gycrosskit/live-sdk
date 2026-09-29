package io.github.gycrosskit.livesdk

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Android AtomicX Store 与 iOS Swift Bridge 共用的 Compose 快照累加器。
 *
 * 平台层只把 SDK 回调转换为中立模型；弹幕去重、有界列表、加载、PiP、主播和观众快照的
 * 状态更新规则只在 commonMain 维护一份。
 */
@Stable
internal class LiveAudienceSnapshotStore(
    liveId: String,
    initialIntroduction: String = "",
) {
    // UI 只观察 messageSnapshot；内部队列不进入 Compose Snapshot，避免每条消息产生两次状态写入。
    private val messages = ArrayDeque<LiveAudienceMessageSnapshot>()
    /** 只在消息真正变化时生成不可变副本，其他直播状态更新不再重复复制整条弹幕列表。 */
    private var messageSnapshot by mutableStateOf(emptyList<LiveAudienceMessageSnapshot>())
    private val knownMessageKeys = linkedSetOf<LiveAudienceMessageIdentity>()
    private var syntheticMessageSequence = -1L
    private var introduction by mutableStateOf(initialIntroduction)
    private var interactionReady by mutableStateOf(false)
    private var loading by mutableStateOf(true)
    private var pictureInPicture by mutableStateOf(false)
    private var likeEffectSequence by mutableLongStateOf(0L)
    private var host by mutableStateOf(emptyLiveAudienceHost(liveId))
    private var audience by mutableStateOf(emptyList<LiveAudienceUserSnapshot>())
    private var audienceCount by mutableStateOf(0)

    /** 每次返回当前状态的不可变副本，避免旧快照随后续 SDK 回调被原地改写。 */
    fun snapshot() = LiveAudienceContentSnapshot(
        messages = messageSnapshot,
        introduction = introduction,
        interactionReady = interactionReady,
        loading = loading,
        pictureInPicture = pictureInPicture,
        likeEffectSequence = likeEffectSequence,
        host = host,
        audience = audience,
        audienceCount = audienceCount,
    )

    fun updateIntroduction(value: String) {
        introduction = value
    }

    fun updateInteractionReady(ready: Boolean) {
        interactionReady = ready
    }

    fun updateLoading(visible: Boolean) {
        loading = visible
    }

    fun updatePictureInPicture(enabled: Boolean) {
        pictureInPicture = enabled
    }

    fun emitLikeEffect() {
        likeEffectSequence++
    }

    fun updateHost(value: LiveAudienceHostSnapshot) {
        host = value
    }

    fun updateFollowState(
        visible: Boolean,
        followed: Boolean,
        requestRunning: Boolean,
        fansCount: Long,
    ) {
        host = host.copy(
            followVisible = visible,
            followed = followed,
            followRequestRunning = requestRunning,
            fansCount = fansCount,
        )
    }

    fun updateAudience(users: List<LiveAudienceUserSnapshot>, count: Int) {
        audience = users.toList()
        audienceCount = maxOf(count, users.size)
    }

    /**
     * 批量追加一次 SDK 状态更新中的新增消息，只生成一份 Compose 快照。
     * 同一 SDK sequence 可能在重连后复用，因此稳定键同时包含发送者、时间和内容。
     */
    fun appendMessages(incoming: Iterable<LiveAudienceMessageSnapshot>): Int {
        var appended = 0
        incoming.forEach { message ->
            if (knownMessageKeys.add(message.identityKey)) {
                messages.addLast(message)
                appended++
            }
        }
        if (appended == 0) return 0
        while (messages.size > MAX_MESSAGE_COUNT) {
            knownMessageKeys.remove(messages.removeFirst().identityKey)
        }
        messageSnapshot = messages.toList()
        return appended
    }

    fun appendMessage(message: LiveAudienceMessageSnapshot): Boolean =
        appendMessages(listOf(message)) > 0

    /**
     * 双端成员事件统一转为语义消息；负序号与 SDK 正常弹幕隔离，显示文案留给 shared 资源系统处理。
     */
    fun appendMemberMessage(
        user: LiveAudienceUserSnapshot,
        joined: Boolean,
        timestampSeconds: Double,
    ): Boolean = appendMessage(
        LiveAudienceMessageSnapshot(
            sequence = syntheticMessageSequence--,
            timestampSeconds = timestampSeconds,
            senderId = user.id,
            senderName = user.name,
            senderAvatarUrl = user.avatarUrl,
            content = "",
            kind = if (joined) {
                LiveAudienceMessageKind.MEMBER_JOINED
            } else {
                LiveAudienceMessageKind.MEMBER_LEFT
            },
        ),
    )
}

/** 使用结构化键避免字段内容包含分隔符时产生误判，同时保留重连后的综合去重语义。 */
private data class LiveAudienceMessageIdentity(
    val senderId: String,
    val sequence: Long,
    val timestampSeconds: Double,
    val content: String,
    val businessId: String,
    val data: String,
    val kind: LiveAudienceMessageKind,
)

private val LiveAudienceMessageSnapshot.identityKey: LiveAudienceMessageIdentity
    get() = LiveAudienceMessageIdentity(
        senderId = senderId,
        sequence = sequence,
        timestampSeconds = timestampSeconds,
        content = content,
        businessId = businessId,
        data = data,
        kind = kind,
    )

private fun emptyLiveAudienceHost(liveId: String) = LiveAudienceHostSnapshot(
    roomId = liveId,
    roomName = "",
    owner = null,
    followVisible = false,
    followed = false,
    followRequestRunning = false,
    fansCount = 0,
)

private const val MAX_MESSAGE_COUNT = 200
