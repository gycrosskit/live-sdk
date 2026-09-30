package io.github.gycrosskit.livesdk

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import android.view.ViewGroup
import io.trtc.tuikit.atomicxcore.api.CompletionHandler
import io.trtc.tuikit.atomicxcore.api.barrage.Barrage
import io.trtc.tuikit.atomicxcore.api.barrage.BarrageStore
import io.trtc.tuikit.atomicxcore.api.live.LiveAudienceListener
import io.trtc.tuikit.atomicxcore.api.live.LiveAudienceStore
import io.trtc.tuikit.atomicxcore.api.live.LiveEndedReason
import io.trtc.tuikit.atomicxcore.api.live.LiveInfo
import io.trtc.tuikit.atomicxcore.api.live.LiveInfoCompletionHandler
import io.trtc.tuikit.atomicxcore.api.live.LiveKickedOutReason
import io.trtc.tuikit.atomicxcore.api.live.LiveListListener
import io.trtc.tuikit.atomicxcore.api.live.LiveListStore
import io.trtc.tuikit.atomicxcore.api.live.LiveUserInfo
import io.trtc.tuikit.atomicxcore.api.view.CoreViewType
import io.trtc.tuikit.atomicxcore.api.view.LiveCoreView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * AtomicX 观看端会话与原生播放器入口。
 *
 * 本类只管理腾讯会话、Store 和 `LiveCoreView` 生命周期；所有直播间操作层 UI 均由 shared CMP 绘制。
 * 类本身不继承 Android View，也不创建 `ComposeView`，确保直播页面只有一个 Compose 根节点。
 */
class AtomicAudienceView(
    private val context: Context,
    private val liveId: String,
    private val listener: AtomicAudienceListener,
    likeReporter: LiveLikeReporter,
) : LiveAudienceCommands {
    // 会话资源：所有 AtomicX Store 和回调都限定在本实例生命周期内。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val liveListStore = LiveListStore.shared()
    // 与 iOS 共用不可变快照和去重规则；Store 回调必须先切回主线程。
    private val snapshotStore = LiveAudienceSnapshotStore(
        liveId = liveId,
        initialIntroduction = context.getString(R.string.atomic_audience_no_introduction),
    )
    private val liveCoreView = LiveCoreView(context, null, 0, CoreViewType.PLAY_VIEW).apply {
        setLiveID(liveId)
    }
    private val roomInfoController = LiveHostFollowController(
        gateway = AndroidLiveHostFollowGateway,
        scope = scope,
        onStateChanged = snapshotStore::updateFollowState,
        onFailure = { description ->
            AtomicAudienceRuntimeRegistry.showMessage(
                context.getString(R.string.atomic_audience_follow_failed, description),
            )
        },
    )
    private val likeController = AtomicLikeController(
        context = context,
        likeReporter = likeReporter,
        onLikeReceived = snapshotStore::emitLikeEffect,
    )
    private var barrageStore: BarrageStore? = null
    private var audienceStore: LiveAudienceStore? = null
    private var interactionStoresReleased = false

    private val liveListListener = object : LiveListListener() {
        override fun onLiveEnded(liveID: String, reason: LiveEndedReason, message: String) {
            if (liveID == liveId) {
                releaseInteractionStores()
                session.liveEnded(message)
            }
        }

        override fun onKickedOutOfLive(
            liveID: String,
            reason: LiveKickedOutReason,
            message: String,
        ) {
            if (liveID == liveId) {
                AtomicAudienceRuntimeRegistry.warning(
                    "AtomicX 当前观众被移出直播间，liveId=$liveId, reason=$reason, message=$message",
                )
                releaseInteractionStores()
                listener.onKickedOut()
            }
        }
    }

    private val liveAudienceListener = object : LiveAudienceListener() {
        override fun onOwnerJoined(owner: LiveUserInfo) = appendMemberMessage(owner, joined = true)

        override fun onOwnerLeft(owner: LiveUserInfo) = appendMemberMessage(owner, joined = false)

        override fun onAdminJoined(admin: LiveUserInfo) = appendMemberMessage(admin, joined = true)

        override fun onAdminLeft(admin: LiveUserInfo) = appendMemberMessage(admin, joined = false)

        override fun onAudienceJoined(audience: LiveUserInfo) = appendMemberMessage(audience, joined = true)

        override fun onAudienceLeft(audience: LiveUserInfo) = appendMemberMessage(audience, joined = false)

        override fun onAudienceMessageDisabled(audience: LiveUserInfo, isDisable: Boolean) {
            if (audience.userID == AtomicXSession.currentUserId()) {
                listener.onCurrentUserMessageDisabled(isDisable)
            }
        }
    }

    private val session = AtomicAudienceSession(
        liveId = liveId,
        messages = AtomicAudienceSessionMessages(
            queueTimeout = context.getString(R.string.atomic_audience_session_queue_timeout),
            joinTimeout = context.getString(R.string.atomic_audience_join_timeout),
            joinInvocationFailed = context.getString(R.string.atomic_audience_join_failed),
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
                releaseRoomResources()
                listener.onJoinFailed(code, message)
            }

            override fun onLiveUnavailable(message: String) {
                snapshotStore.updateLoading(false)
                releaseRoomResources()
                listener.onLiveUnavailable(message)
            }

            override fun onLiveEnded() {
                snapshotStore.updateLoading(false)
                releaseRoomResources()
                listener.onLiveEnded()
            }
        },
        join = ::joinLive,
        leave = ::leaveLive,
    )

    /**
     * 向 shared 操作层暴露只读快照。两种 UI 通过同一个 StateFlow 观察更新，
     * 但 AtomicX 的 Barrage、LiveInfo 与 LiveUserInfo 不会越过模块边界。
     */
    override val snapshots: kotlinx.coroutines.flow.StateFlow<LiveAudienceContentSnapshot> get() = snapshotStore.snapshots

    override val snapshot: LiveAudienceContentSnapshot
        get() = snapshotStore.snapshot()

    val nativeView: android.view.View get() = liveCoreView

    override fun like() = likeController.like()

    override fun toggleFollow() = roomInfoController.toggleFollow()

    /**
     * 发起系统画中画请求。这里只在平台 View 层解析 Activity，不把 Android 宿主类型泄漏到 common。
     */
    override fun enterPictureInPicture(wideContent: Boolean): Boolean {
        val activity = context.findActivity() ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (!activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            return false
        }
        updatePictureInPicture(true)
        val ratio = if (wideContent) Rational(16, 9) else Rational(9, 16)
        val accepted = activity.enterPictureInPictureMode(
            PictureInPictureParams.Builder().setAspectRatio(ratio).build(),
        )
        if (!accepted) updatePictureInPicture(false)
        return accepted
    }

    /** 同步宿主 Activity 回报的系统画中画实际状态。 */
    override fun updatePictureInPicture(enabled: Boolean) {
        snapshotStore.updatePictureInPicture(enabled)
    }

    /**
     * 幂等释放当前观看实例。已进房时必须等待腾讯离房回调或超时兜底后才释放进程级会话令牌。
     */
    override fun release() {
        AtomicAudienceRuntimeRegistry.info("释放 AtomicX 观看会话，liveId=$liveId")
        releaseRoomResources()
        session.release()
    }

    private fun joinLive() {
        liveListStore.addLiveListListener(liveListListener)
        liveListStore.joinLive(
            liveId,
            object : LiveInfoCompletionHandler {
                override fun onSuccess(liveInfo: LiveInfo) {
                    post {
                        updateLiveInfo(liveInfo)
                        bindInteractionStores()
                        session.joined()
                    }
                }

                override fun onFailure(code: Int, desc: String) {
                    post { session.joinFailed(code, desc) }
                }
            },
        )
    }

    private fun bindInteractionStores() {
        if (interactionStoresReleased) return
        likeController.bind(liveId)
        barrageStore = try {
            BarrageStore.create(liveId).also { store ->
                scope.launch {
                    store.barrageState.messageList.collectLatest(::appendNewBarrages)
                }
            }
        } catch (error: Throwable) {
            AtomicAudienceRuntimeRegistry.warning(
                "AtomicX 弹幕 Store 初始化失败，直播继续以只读模式运行，liveId=$liveId",
                error,
            )
            null
        }
        snapshotStore.updateInteractionReady(barrageStore != null)
        audienceStore = try {
            LiveAudienceStore.create(liveId).also { store ->
                store.addLiveAudienceListener(liveAudienceListener)
                scope.launch {
                    combine(
                        store.liveAudienceState.audienceList,
                        store.liveAudienceState.audienceCount,
                    ) { users, count -> users to count }.collect { (users, count) ->
                        snapshotStore.updateAudience(
                            users = users.map { user ->
                                LiveAudienceUserSnapshot(user.userID, user.userName, user.avatarURL)
                            },
                            count = count,
                        )
                    }
                }
                refreshAudience(store)
            }
        } catch (error: Throwable) {
            AtomicAudienceRuntimeRegistry.warning(
                "AtomicX 观众 Store 初始化失败，直播继续播放，liveId=$liveId",
                error,
            )
            null
        }
    }

    private fun updateLiveInfo(liveInfo: LiveInfo) {
        val owner = liveInfo.liveOwner
        snapshotStore.updateHost(
            LiveAudienceHostSnapshot(
                roomId = liveInfo.liveID,
                roomName = liveInfo.liveName,
                owner = LiveAudienceUserSnapshot(owner.userID, owner.userName, owner.avatarURL),
                followVisible = false,
                followed = false,
                followRequestRunning = false,
                fansCount = 0L,
            ),
        )
        roomInfoController.bind(liveInfo.liveOwner.userID)
        snapshotStore.updateIntroduction(liveInfo.notice.ifBlank {
            liveInfo.liveName.ifBlank { context.getString(R.string.atomic_audience_no_introduction) }
        })
    }

    override fun refreshAudience() = refreshAudience(audienceStore)

    private fun refreshAudience(store: LiveAudienceStore?) {
        if (store == null) return
        try {
            store.fetchAudienceList(
                object : CompletionHandler {
                    override fun onSuccess() = Unit

                    override fun onFailure(code: Int, desc: String) {
                        AtomicAudienceRuntimeRegistry.warning(
                            "AtomicX 刷新在线观众失败，liveId=$liveId, code=$code, message=$desc",
                        )
                    }
                },
            )
        } catch (error: Throwable) {
            AtomicAudienceRuntimeRegistry.warning(
                "AtomicX 刷新在线观众异常，liveId=$liveId",
                error,
            )
        }
    }

    override fun sendBarrage(message: String, onFinished: (Boolean, String) -> Unit) {
        val store = barrageStore
        if (store == null) {
            post { onFinished(false, context.getString(R.string.atomic_audience_not_ready)) }
            return
        }
        try {
            store.sendTextMessage(
                message,
                emptyMap(),
                object : CompletionHandler {
                    override fun onSuccess() {
                        post { onFinished(true, "") }
                    }

                    override fun onFailure(code: Int, desc: String) {
                        AtomicAudienceRuntimeRegistry.warning(
                            "AtomicX 弹幕发送失败，liveId=$liveId, code=$code, message=$desc",
                        )
                        post { onFinished(false, desc) }
                    }
                },
            )
        } catch (error: Throwable) {
            AtomicAudienceRuntimeRegistry.warning(
                "AtomicX 弹幕发送异常，liveId=$liveId",
                error,
            )
            post { onFinished(false, context.getString(R.string.atomic_audience_not_ready)) }
        }
    }

    /** Android 只适配 AtomicX CompletionHandler；离房超时和队列放行由 common 状态机负责。 */
    private fun leaveLive(onFinished: (Boolean, Int, String) -> Unit) {
        liveListStore.leaveLive(
            object : CompletionHandler {
                override fun onSuccess() = onFinished(true, 0, "")

                override fun onFailure(code: Int, desc: String) =
                    onFinished(false, code, desc)
            },
        )
    }

    /**
     * 弹幕、观众和点赞 Store 只服务已加入的当前房间。关播时先停掉这些流与监听，即使宿主退出动画尚未完成，
     * 迟到回调也不会继续修改 Compose 状态；真正的共享 LiveListStore 仍由离房流程单独释放。
     */
    private fun releaseInteractionStores() {
        if (interactionStoresReleased) return
        interactionStoresReleased = true
        scope.cancel()
        audienceStore?.removeLiveAudienceListener(liveAudienceListener)
        audienceStore = null
        barrageStore = null
        snapshotStore.updateInteractionReady(false)
        likeController.release()
    }

    /**
     * 终态和宿主退出共用同一清理入口。完整观看令牌仍由 [AtomicAudienceSession] 在真实离房回调后释放，
     * 这里只尽早切断当前房间监听，防止错误页或退出动画期间的迟到事件继续修改页面快照。
     */
    private fun releaseRoomResources() {
        liveListStore.removeLiveListListener(liveListListener)
        releaseInteractionStores()
        roomInfoController.release()
    }

    private fun appendNewBarrages(incoming: List<Barrage>) {
        snapshotStore.appendMessages(incoming.map(Barrage::toAudienceMessageSnapshot))
    }

    /**
     * 成员进退房只消费 AtomicX AudienceStore 事件，避免再与 IM 群成员回调重复计数和重复展示。
     * 人工弹幕保留完整 sender 副本，因此消息列表能继续显示成员昵称和头像。
     */
    private fun appendMemberMessage(userInfo: LiveUserInfo, joined: Boolean) {
        post {
            if (!session.isJoined) return@post
            snapshotStore.appendMemberMessage(
                user = LiveAudienceUserSnapshot(
                    id = userInfo.userID,
                    name = userInfo.userName,
                    avatarUrl = userInfo.avatarURL,
                ),
                joined = joined,
                timestampSeconds = System.currentTimeMillis() / 1_000.0,
            )
        }
    }

    /** 腾讯回调不保证线程；所有状态修改统一回到主线程。 */
    private fun post(block: () -> Unit) {
        AtomicMainThread.post(block)
    }

}

/** 逐层解包 Compose 提供的 ContextWrapper，找不到 Activity 时由调用方按不支持 PiP 处理。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Barrage.toAudienceMessageSnapshot() = LiveAudienceMessageSnapshot(
    sequence = sequence,
    timestampSeconds = timestampInSecond.toDouble(),
    senderId = sender.userID,
    senderName = sender.userName,
    senderAvatarUrl = sender.avatarURL,
    content = textContent,
    businessId = businessID,
    data = data,
)
