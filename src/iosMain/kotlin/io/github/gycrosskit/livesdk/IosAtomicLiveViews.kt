package io.github.gycrosskit.livesdk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import platform.UIKit.UIView

/** iOS 列表预览控件；shared 只消费中立状态，不再直接创建或释放 `UIView`。 */
@OptIn(ExperimentalForeignApi::class)
@Composable
internal fun IosAtomicLivePreview(
    bridge: IosLiveSdkBridge,
    liveId: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onStateChanged: (LivePreviewState) -> Unit = {},
) {
    var nativeView by remember(liveId) { mutableStateOf<UIView?>(null) }
    val playback = remember(bridge, liveId) {
        IosAtomicLivePreviewPlayback(
            bridge = bridge,
            onViewChanged = { nativeView = it },
        )
    }
    rememberAtomicLivePreviewController(liveId, active, playback, onStateChanged)
    nativeView?.let { view ->
        UIKitView(
            factory = { view },
            modifier = modifier,
            onRelease = playback::releaseView,
            // 列表卡片点击属于上层 CMP；原生预览只渲染画面，不能截断父级点击手势。
            properties = PassiveLiveViewProperties,
        )
    }
}

/** iOS 只实现 UIView 的创建/释放，预览状态机和生命周期门禁复用 commonMain。 */
private class IosAtomicLivePreviewPlayback(
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

    fun releaseView(releasedView: UIView) {
        if (view !== releasedView) return
        view = null
        onViewChanged(null)
        bridge.releasePreviewView(releasedView)
    }
}

/**
 * iOS AtomicX 观看会话和原生播放 View。
 *
 * SDK 回调只更新模块内的中立快照；弹幕列表、资料面板和按钮等可见 UI 仍由 shared CMP 绘制。
 */
internal class IosAtomicAudienceView(
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
    private var sessionGranted by mutableStateOf(false)
    private var resourcesReleased = false
    private val likeBatcher = LiveLikeBatcher(
        send = { count, onFinished ->
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
        // common 状态机获得共享 Store 令牌后才允许 Compose 创建 Swift LiveCoreView。
        join = { sessionGranted = true },
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
            AtomicAudienceRuntimeRegistry.warning("iOS AtomicX 当前观众被移出直播间，liveId=$liveId")
            listener.onKickedOut()
        }

        override fun onInteractionReady() {
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
            if (count > 0) snapshotStore.emitLikeEffect()
        }

        override fun onAudienceChanged(
            userIds: List<String>,
            userNames: List<String>,
            userAvatarUrls: List<String>,
            count: Int,
        ) {
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
            snapshotStore.appendMemberMessage(
                user = LiveAudienceUserSnapshot(userId, userName, userAvatarUrl),
                joined = joined,
                timestampSeconds = timestampSeconds,
            )
        }

        override fun onAudienceMessageDisabled(userId: String, disabled: Boolean) {
            if (userId == bridge.currentUserId()) {
                listener.onCurrentUserMessageDisabled(disabled)
            }
        }

        override fun onPictureInPictureChanged(enabled: Boolean) {
            snapshotStore.updatePictureInPicture(enabled)
            listener.onPictureInPictureChanged(enabled)
        }
    }

    /** Compose 读取时由模块内 Snapshot State 驱动重组，Swift 和 UIKit 类型不会越过边界。 */
    override val snapshot: LiveAudienceContentSnapshot
        get() = snapshotStore.snapshot()

    @Composable
    fun Player(modifier: Modifier = Modifier) {
        if (!sessionGranted) return
        UIKitView(
            factory = {
                bridge.makeAudienceView(liveId, observer).also { nativeView = it }
            },
            modifier = modifier,
            onRelease = { releasedView ->
                if (nativeView === releasedView) release()
            },
            // 连击点赞和清屏均由 shared CMP 处理；AtomicX View 不参与命中测试。
            properties = PassiveLiveViewProperties,
        )
    }

    override fun sendBarrage(message: String, onFinished: (Boolean, String) -> Unit) {
        bridge.sendBarrage(message, object : IosLiveOperationCallback {
            override fun onSuccess() = onFinished(true, "")

            override fun onFailure(code: Int, message: String) {
                AtomicAudienceRuntimeRegistry.warning(
                    "iOS AtomicX 弹幕发送失败，liveId=$liveId, code=$code, message=$message",
                )
                onFinished(false, message)
            }
        })
    }

    override fun like() = likeBatcher.like()

    override fun toggleFollow() = roomInfoController.toggleFollow()

    override fun refreshAudience() = bridge.refreshAudience()

    override fun enterPictureInPicture(wideContent: Boolean): Boolean =
        bridge.enterPictureInPicture(wideContent)

    override fun updatePictureInPicture(enabled: Boolean) {
        snapshotStore.updatePictureInPicture(enabled)
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

/** iOS 原生直播 View 是纯视频层，全部业务手势和可访问性语义由外层 CMP 提供。 */
private val PassiveLiveViewProperties = UIKitInteropProperties(
    isInteractive = false,
    isNativeAccessibilityEnabled = false,
)

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
