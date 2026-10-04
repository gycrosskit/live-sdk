package io.github.gycrosskit.livesdk


/**
 * Android AtomicX Store 与 iOS Swift Bridge 共用的 StateFlow 快照累加器。
 *
 * 平台层只把 SDK 回调转换为中立模型；弹幕去重、有界列表、加载、PiP、主播和观众快照的
 * 状态更新规则只在 commonMain 维护一份。
 */
internal class LiveAudienceSnapshotStore(
    liveId: String,
    initialIntroduction: String = "",
) {
    // 内部去重队列不直接暴露给 UI，只发布不可变快照。
    private val messages = ArrayDeque<LiveAudienceMessageSnapshot>()
    private val knownMessageKeys = linkedSetOf<LiveAudienceMessageIdentity>()
    private var syntheticMessageSequence = -1L

    // 两端 SDK 回调统一在主线程更新；快照只由这个 StateFlow 持有。
    val snapshots = kotlinx.coroutines.flow.MutableStateFlow(
        LiveAudienceContentSnapshot.Empty.copy(
            introduction = initialIntroduction,
            host = emptyLiveAudienceHost(liveId),
        ),
    )

    /** 读取已发布快照；后续回调通过 copy 更新，不会原地改写旧快照。 */
    fun snapshot() = snapshots.value

    fun updateIntroduction(value: String) {
        snapshots.value = snapshot().copy(introduction = value)
    }

    fun updateInteractionReady(ready: Boolean) {
        snapshots.value = snapshot().copy(interactionReady = ready)
    }

    fun updateLoading(visible: Boolean) {
        snapshots.value = snapshot().copy(loading = visible)
    }

    fun updatePictureInPicture(enabled: Boolean) {
        snapshots.value = snapshot().copy(pictureInPicture = enabled)
    }

    fun emitLikeEffect() {
        val current = snapshot()
        snapshots.value = current.copy(likeEffectSequence = current.likeEffectSequence + 1)
    }

    fun updateHost(value: LiveAudienceHostSnapshot) {
        snapshots.value = snapshot().copy(host = value)
    }

    fun updateFollowState(
        visible: Boolean,
        followed: Boolean,
        requestRunning: Boolean,
        fansCount: Long,
    ) {
        val current = snapshot()
        snapshots.value = current.copy(host = current.host.copy(
            followVisible = visible,
            followed = followed,
            followRequestRunning = requestRunning,
            fansCount = fansCount,
        ))
    }

    fun updateAudience(users: List<LiveAudienceUserSnapshot>, count: Int) {
        snapshots.value = snapshot().copy(audience = users.toList(), audienceCount = maxOf(count, users.size))
    }

    /**
     * 批量追加一次 SDK 状态更新中的新增消息，只发布一次快照。
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
        snapshots.value = snapshot().copy(messages = messages.toList())
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
