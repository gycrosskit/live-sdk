package io.github.gycrosskit.livesdk

/**
 * 鸿蒙桥复用公共观看排队、快照和点赞批处理；不持有 ArkUI/腾讯 SDK 类型。
 * 创建、命令、回调与 release 均在 Main；账号与视频节点由平台桥准备。
 */
class LiveBridgeAudienceSession internal constructor(
    private val liveId: String,
    private val platform: LiveAudiencePlatform,
    private val callbacks: LivePlaybackCallbacks,
    gate: AtomicAudienceSessionGate,
    scope: kotlinx.coroutines.CoroutineScope,
) : LiveAudienceCommands {
    constructor(liveId: String, platform: LiveAudiencePlatform, callbacks: LivePlaybackCallbacks) : this(
        liveId, platform, callbacks, AtomicAudienceSessionCoordinator,
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate),
    )
    private val store = LiveAudienceSnapshotStore(liveId)
    private var released = false
    private val likes: LiveLikeBatcher = LiveLikeBatcher(send = { count, done ->
        if (session.isJoined) platform.sendLike(count, done) else done(false)
    })
    private val observer = object : LiveAudiencePlatformObserver {
        override fun joined(host: LiveAudienceHostSnapshot, introduction: String) {
            session.joined {
                if (!released) {
                    store.updateHost(host)
                    store.updateIntroduction(introduction)
                    store.updateInteractionReady(true)
                }
            }
        }
        override fun joinFailed(code: Int, message: String) = session.joinFailed(code, message)
        override fun liveEnded() = session.liveEnded()
        override fun kickedOut() {
            if (released) return
            release()
            callbacks.onKickedOut()
        }
        override fun messages(values: List<LiveAudienceMessageSnapshot>) {
            if (!released && session.isJoined) store.appendMessages(values)
        }
        override fun memberChanged(user: LiveAudienceUserSnapshot, joined: Boolean, timestampSeconds: Double) {
            if (!released && session.isJoined) store.appendMemberMessage(user, joined, timestampSeconds)
        }
        override fun audience(users: List<LiveAudienceUserSnapshot>, count: Int) {
            if (!released && session.isJoined) store.updateAudience(users, count)
        }
        override fun likes(count: Int) {
            if (!released && session.isJoined && count > 0) store.emitLikeEffect()
        }
        override fun pictureInPictureChanged(enabled: Boolean) {
            if (!released && session.isJoined) {
                store.updatePictureInPicture(enabled)
                callbacks.onPictureInPictureChanged(enabled)
            }
        }
        override fun messageDisabled(disabled: Boolean) {
            if (!released && session.isJoined) callbacks.onCurrentUserMessageDisabled(disabled)
        }
    }
    private val session: AtomicAudienceSession = AtomicAudienceSession(
        liveId,
        AtomicAudienceSessionMessages("直播间排队超时", "进入直播间超时", "进入直播间失败"),
        object : AtomicAudienceSessionEvents {
            override fun onJoinStarted() = callbacks.onJoinStarted()
            override fun onJoinSucceeded() {
                store.updateLoading(false)
                callbacks.onJoinSucceeded()
            }
            override fun onJoinFailed(code: Int, message: String) {
                finish()
                callbacks.onJoinFailed(code, message)
            }
            override fun onLiveUnavailable(message: String) {
                finish()
                callbacks.onLiveUnavailable(message)
            }
            override fun onLiveEnded() {
                finish()
                callbacks.onLiveEnded()
            }
        },
        join = { platform.join(liveId, observer) },
        leave = { done -> finish(); platform.leave(done) },
        gate = gate,
        scope = scope,
    )
    override val snapshots: kotlinx.coroutines.flow.StateFlow<LiveAudienceContentSnapshot> get() = store.snapshots
    override val snapshot get() = store.snapshot()

    override fun sendBarrage(message: String, onFinished: (Boolean, String) -> Unit) {
        if (released || !session.isJoined || message.isBlank()) { onFinished(false, "Not ready"); return }
        platform.sendBarrage(message) { success, description ->
            val current = !released && session.isJoined
            onFinished(success && current, if (current) description else "Released")
        }
    }
    override fun like() { if (!released && session.isJoined) likes.like() }
    override fun refreshAudience() { if (!released && session.isJoined) platform.refreshAudience() }
    // 关注暂未接入；系统 PiP 请求由原生视频节点受理，快照只消费真实系统回调。
    override fun toggleFollow() = Unit
    override fun enterPictureInPicture(wideContent: Boolean) = false
    override fun updatePictureInPicture(enabled: Boolean) = Unit
    override fun release() { finish(); session.release() }

    private fun finish() {
        if (released) return
        // 仍持有 joined 令牌时冲刷尾批；其后所有互动立即失效。
        likes.release()
        released = true
        store.updatePictureInPicture(false)
        store.updateInteractionReady(false)
        store.updateLoading(false)
    }
}
