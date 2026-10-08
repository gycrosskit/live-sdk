@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.gycrosskit.livesdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import platform.UIKit.UIView

/**
 * iOS 只实现 UIView 的创建/释放，预览状态机和生命周期门禁复用 commonMain。
 *
 * @param bridge 已安装的 Swift 原生桥，不归本实例注销。
 * @param onViewChanged Main 上交付当前原生 View；停止或释放时传 null。
 */
class IosAtomicLivePreviewPlayback(
    private val bridge: IosLiveSdkBridge,
    private val onViewChanged: (UIView?) -> Unit,
) : AtomicLivePreviewPlayback {
    private var view: UIView? = null

    override fun start(liveId: String, callback: AtomicLivePreviewPlayback.Callback) {
        val created = bridge.makePreviewView(
            liveId,
            object : IosLivePreviewObserver {
                override fun onLoading() = callback.onLoading()

                override fun onPlaying() = callback.onPlaying()

                override fun onFailed(code: Int, message: String) {
                    callback.onFailure(code, message)
                }
            },
        )
        view = created
        onViewChanged(created)
    }

    override fun stopAndDetach(liveId: String) {
        view?.let(::releaseView)
    }

    /**
     * 仅释放仍属于当前预览的 View，旧 UIKit dispose 不得停掉新实例。
     *
     * @param releasedView 待释放的 View，只有与当前实例身份一致时才生效。
     */
    fun releaseView(releasedView: UIView) {
        if (view !== releasedView) return
        view = null
        releasedView.removeFromSuperview()
        onViewChanged(null)
        bridge.releasePreviewView(releasedView)
    }
}

/**
 * iOS AtomicX 观看会话和原生播放 View。
 *
 * SDK 回调只更新模块内的中立快照；弹幕列表、资料面板和按钮等可见 UI 由宿主绘制。
 *
 * @param bridge 已安装的 Swift 原生桥，不归本实例注销。
 * @param liveId 当前实例的腾讯直播间 ID，实例不可跨房间复用。
 * @param listener Main 上交付观看事件的监听器，释放后不再交付有效会话事件。
 * @param likeReporter SDK 成功的点赞批次上报，不执行阻塞任务。
 */
class IosAtomicAudienceView(
    private val bridge: IosLiveSdkBridge,
    private val liveId: String,
    private val listener: AtomicAudienceListener,
    private val likeReporter: LiveLikeReporter,
) : LiveAudienceCommands {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val snapshotStore = LiveAudienceSnapshotStore(liveId)
    private val roomInfoController = LiveHostFollowController(
        gateway = IosLiveHostFollowGateway(bridge),
        scope = scope,
        onStateChanged = snapshotStore::updateFollowState,
        onFailure = { description ->
            val detail = description.takeIf(String::isNotBlank)?.let { "：$it" }.orEmpty()
            AtomicAudienceRuntimeRegistry.showMessage("关注操作失败$detail")
        },
    )
    private var sessionGranted = false
    private var resourcesReleased = false
    private val likeBatcher = LiveLikeBatcher(
        send = send@ { count, onFinished ->
            // Bridge 是全局当前 Store；排队或已交出令牌的实例不能发送尾批次。
            if (!ownsJoinedSession()) { onFinished(false); return@send }
            val reportingUserId = bridge.currentUserId()
            bridge.sendLike(count, object : IosLiveOperationCallback {
                override fun onSuccess() {
                    likeReporter.report(liveId, reportingUserId, count)
                    onFinished(true)
                }

                override fun onFailure(code: Int, message: String) = onFinished(false)
            })
        },
        onRetryScheduled = {
            AtomicAudienceRuntimeRegistry.warning(
                "iOS AtomicX 点赞发送失败，已保留批次等待重试，liveId=$liveId",
            )
        },
        onSendException = { error ->
            AtomicAudienceRuntimeRegistry.error(
                "iOS AtomicX 点赞调用异常，liveId=$liveId",
                error,
            )
        },
    )
    private var nativeView: UIView? = null
    private var nativeHost: UIView? = null

    private val session = AtomicAudienceSession(
        liveId = liveId,
        messages = AtomicAudienceSessionMessages(
            queueTimeout = "直播间排队超时，请稍后重试",
            joinTimeout = "进入直播间超时，请稍后重试",
            joinInvocationFailed = "进入直播间失败，请稍后重试",
        ),
        events = object : AtomicAudienceSessionEvents {
            override fun onJoinStarted() {
                snapshotStore.updateLoading(true)
                listener.onJoinStarted()
            }

            override fun onJoinSucceeded() {
                snapshotStore.updateLoading(false)
                listener.onJoinSucceeded()
            }

            override fun onJoinFailed(code: Int, message: String) {
                snapshotStore.updateLoading(false)
                releaseResources()
                listener.onJoinFailed(code, message)
            }

            override fun onLiveUnavailable(message: String) {
                snapshotStore.updateLoading(false)
                releaseResources()
                listener.onLiveUnavailable(message)
            }

            override fun onLiveEnded() {
                snapshotStore.updateLoading(false)
                releaseResources()
                listener.onLiveEnded()
            }
        },
        // common 状态机获得共享 Store 令牌后才允许创建 Swift LiveCoreView。
        join = {
            sessionGranted = true
            nativeHost?.let(::mount)
        },
        leave = ::leaveLive,
    )

    private val observer = object : IosAudiencePlayerObserver {
        override fun onJoinSucceeded() = session.joined()

        override fun onLiveInfo(
            roomId: String,
            liveName: String,
            notice: String,
            ownerId: String,
            ownerName: String,
            ownerAvatarUrl: String,
        ) {
            if (resourcesReleased) return
            snapshotStore.updateIntroduction(notice.ifBlank { liveName })
            snapshotStore.updateHost(
                LiveAudienceHostSnapshot(
                    roomId = roomId,
                    roomName = liveName,
                    owner = LiveAudienceUserSnapshot(ownerId, ownerName, ownerAvatarUrl),
                    // 可见性和关注状态由 common 状态机根据当前账号重新计算，不信任平台缓存。
                    followVisible = false,
                    followed = false,
                    followRequestRunning = false,
                    fansCount = 0L,
                ),
            )
            roomInfoController.bind(ownerId)
        }

        override fun onJoinFailed(code: Int, message: String) = session.joinFailed(code, message)

        override fun onLiveEnded() = session.liveEnded()

        override fun onLiveUnavailable(message: String) = session.liveUnavailable(message)

        override fun onKickedOut() {
            if (resourcesReleased) return
            AtomicAudienceRuntimeRegistry.warning("iOS AtomicX 当前观众被移出直播间，liveId=$liveId")
            releaseResources()
            session.release()
            listener.onKickedOut()
        }

        override fun onInteractionReady() {
            if (resourcesReleased) return
            snapshotStore.updateInteractionReady(true)
        }

        override fun onBarrageReceived(
            sequence: Long,
            timestampSeconds: Double,
            senderId: String,
            senderName: String,
            senderAvatarUrl: String,
            content: String,
        ) {
            if (resourcesReleased) return
            snapshotStore.appendMessage(
                LiveAudienceMessageSnapshot(
                    sequence = sequence,
                    timestampSeconds = timestampSeconds,
                    senderId = senderId,
                    senderName = senderName,
                    senderAvatarUrl = senderAvatarUrl,
                    content = content,
                ),
            )
        }

        override fun onLikesReceived(count: Int) {
            if (resourcesReleased) return
            if (count > 0) snapshotStore.emitLikeEffect()
        }

        override fun onAudienceChanged(
            userIds: List<String>,
            userNames: List<String>,
            userAvatarUrls: List<String>,
            count: Int,
        ) {
            if (resourcesReleased) return
            val users = userIds.indices.map { index ->
                LiveAudienceUserSnapshot(
                    id = userIds[index],
                    name = userNames.getOrElse(index) { "" },
                    avatarUrl = userAvatarUrls.getOrElse(index) { "" },
                )
            }
            snapshotStore.updateAudience(users, count)
        }

        override fun onMemberChanged(
            joined: Boolean,
            userId: String,
            userName: String,
            userAvatarUrl: String,
            timestampSeconds: Double,
        ) {
            if (resourcesReleased) return
            snapshotStore.appendMemberMessage(
                user = LiveAudienceUserSnapshot(userId, userName, userAvatarUrl),
                joined = joined,
                timestampSeconds = timestampSeconds,
            )
        }

        override fun onAudienceMessageDisabled(userId: String, disabled: Boolean) {
            if (resourcesReleased) return
            if (userId == bridge.currentUserId()) {
                listener.onCurrentUserMessageDisabled(disabled)
            }
        }

        override fun onPictureInPictureChanged(enabled: Boolean) {
            if (resourcesReleased) return
            snapshotStore.updatePictureInPicture(enabled)
            listener.onPictureInPictureChanged(enabled)
        }
    }

    /** UI 通过 StateFlow 读取中立快照，Swift 和 UIKit 类型不会越过此状态边界。 */
    override val snapshots: kotlinx.coroutines.flow.StateFlow<LiveAudienceContentSnapshot> get() = snapshotStore.snapshots

    override val snapshot: LiveAudienceContentSnapshot
        get() = snapshotStore.snapshot()

    /**
     * Kuikly 与 CMP 共用观看会话；只有取得会话令牌后才创建 SDK View。
     *
     * @param host 宿主拥有的 UIView 容器，仅 Main 挂载和移除子 View。
     */
    fun mount(host: UIView) {
        nativeHost = host
        if (!sessionGranted || nativeView != null) return
        val view = bridge.makeAudienceView(liveId, observer)
        nativeView = view
        view.setFrame(host.bounds)
        view.autoresizingMask = platform.UIKit.UIViewAutoresizingFlexibleWidth or
            platform.UIKit.UIViewAutoresizingFlexibleHeight
        host.addSubview(view)
    }

    private fun ownsJoinedSession(): Boolean = sessionGranted && session.isJoined && nativeView != null

    override fun sendBarrage(message: String, onFinished: (Boolean, String) -> Unit) {
        if (resourcesReleased || !ownsJoinedSession()) { onFinished(false, "Not ready"); return }
        bridge.sendBarrage(message, object : IosLiveOperationCallback {
            override fun onSuccess() {
                val current = !resourcesReleased && ownsJoinedSession()
                onFinished(current, if (current) "" else "Released")
            }

            override fun onFailure(code: Int, message: String) {
                AtomicAudienceRuntimeRegistry.warning(
                    "iOS AtomicX 弹幕发送失败，liveId=$liveId, code=$code, message=$message",
                )
                onFinished(false, message)
            }
        })
    }

    override fun like() { if (!resourcesReleased && ownsJoinedSession()) likeBatcher.like() }

    override fun toggleFollow() = roomInfoController.toggleFollow()

    override fun refreshAudience() { if (!resourcesReleased && ownsJoinedSession()) bridge.refreshAudience() }

    override fun enterPictureInPicture(wideContent: Boolean): Boolean =
        !resourcesReleased && session.isJoined && bridge.enterPictureInPicture(wideContent)

    override fun updatePictureInPicture(enabled: Boolean) {
        if (resourcesReleased || snapshot.pictureInPicture == enabled) return
        snapshotStore.updatePictureInPicture(enabled)
        listener.onPictureInPictureChanged(enabled)
    }

    override fun release() {
        AtomicAudienceRuntimeRegistry.info("iOS AtomicX 释放观看会话，liveId=$liveId")
        releaseResources()
        session.release()
    }

    /** Swift 只执行真实 UIView 释放和 leaveLive；超时与下一会话放行由 common 状态机负责。 */
    private fun leaveLive(onFinished: (Boolean, Int, String) -> Unit) {
        sessionGranted = false
        val view = nativeView
        nativeView = null
        nativeHost = null
        view?.removeFromSuperview()
        if (view == null) {
            onFinished(true, 0, "")
            return
        }
        bridge.releaseAudienceView(
            view,
            object : IosLiveOperationCallback {
                override fun onSuccess() = onFinished(true, 0, "")

                override fun onFailure(code: Int, message: String) =
                    onFinished(false, code, message)
            },
        )
    }

    private fun releaseResources() {
        if (resourcesReleased) return
        resourcesReleased = true
        snapshotStore.updateInteractionReady(false)
        roomInfoController.release()
        scope.cancel()
        likeBatcher.release()
    }
}

/** iOS Kotlin 侧只把 Swift V2TIM 回调转换为 common 关注状态机结果。 */
private class IosLiveHostFollowGateway(
    private val bridge: IosLiveSdkBridge,
) : LiveHostFollowGateway {
    override val currentUserId: String
        get() = bridge.currentUserId()

    override fun checkFollowed(
        userId: String,
        callback: (LiveFollowResult<Boolean>) -> Unit,
    ) {
        bridge.checkFollowed(
            userId,
            object : IosLiveBooleanCallback {
                override fun onSuccess(value: Boolean) =
                    callback(LiveFollowResult.Success(value))

                override fun onFailure(code: Int, message: String) =
                    callback(LiveFollowResult.Failure("code=$code, $message"))
            },
        )
    }

    override fun updateFollow(
        userId: String,
        followed: Boolean,
        callback: (LiveFollowResult<Unit>) -> Unit,
    ) {
        bridge.updateFollow(
            userId,
            followed,
            object : IosLiveOperationCallback {
                override fun onSuccess() = callback(LiveFollowResult.Success(Unit))

                override fun onFailure(code: Int, message: String) =
                    callback(LiveFollowResult.Failure("code=$code, $message"))
            },
        )
    }

    override fun fetchFans(
        userId: String,
        callback: (LiveFollowResult<Long>) -> Unit,
    ) {
        bridge.fetchFans(
            userId,
            object : IosLiveLongCallback {
                override fun onSuccess(value: Long) =
                    callback(LiveFollowResult.Success(value))

                override fun onFailure(code: Int, message: String) =
                    callback(LiveFollowResult.Failure("code=$code, $message"))
            },
        )
    }
}
