package io.github.gycrosskit.livesdk

import android.content.Context
import androidx.compose.runtime.Stable
import io.trtc.tuikit.atomicxcore.api.CompletionHandler
import io.trtc.tuikit.atomicxcore.api.live.LikeListener
import io.trtc.tuikit.atomicxcore.api.live.LikeStore
import io.trtc.tuikit.atomicxcore.api.live.LiveUserInfo

/**
 * 管理 Android LikeStore 发送和远端点赞事件；合并策略与动画状态分别复用 commonMain 能力。
 *
 * 本地连点按固定窗口合并，节流使用单调时钟，不受用户修改系统时间影响；SDK 失败只在当前房间仍绑定时
 * 恢复批次。切房或释放后的迟到事件会被丢弃，不能继续驱动已经离开的 Compose 页面。
 */
@Stable
internal class AtomicLikeController(
    private val context: Context,
    private val likeReporter: LiveLikeReporter = LiveLikeReporter.None,
    private val onLikeReceived: () -> Unit,
) {
    private var boundLiveId = ""
    private var store: LikeStore? = null
    private var likeBatcher: LiveLikeBatcher? = null
    private var released = false
    private val likeListener = object : LikeListener() {
        override fun onReceiveLikesMessage(liveID: String, totalLikesReceived: Long, sender: LiveUserInfo) {
            AtomicMainThread.post {
                if (!released && store != null && liveID == boundLiveId) onLikeReceived()
            }
        }
    }

    fun bind(liveId: String) {
        if (released || liveId.isBlank()) return
        if (boundLiveId == liveId && store != null) return
        if (store != null) {
            // 控制器按单场直播创建；异常重绑时宁可丢弃旧批次，也不能把旧房间点赞发给新房间。
            AtomicAudienceRuntimeRegistry.warning(
                "AtomicX 点赞控制器发生跨房间重绑，oldLiveId=$boundLiveId, newLiveId=$liveId",
            )
            likeBatcher?.release(flushPending = false)
            likeBatcher = null
            store?.removeLikeListener(likeListener)
        }
        boundLiveId = liveId
        store = try {
            LikeStore.create(liveId).also { it.addLikeListener(likeListener) }
        } catch (error: Throwable) {
            boundLiveId = ""
            AtomicAudienceRuntimeRegistry.warning(
                "AtomicX 点赞 Store 初始化失败，liveId=$liveId",
                error,
            )
            null
        }
        val activeStore = store ?: return
        likeBatcher = LiveLikeBatcher(
            send = { count, onFinished ->
                val reportingUserId = AtomicXSession.currentUserId()
                activeStore.sendLike(
                    count,
                    object : CompletionHandler {
                        override fun onSuccess() {
                            likeReporter.report(liveId, reportingUserId, count)
                            onFinished(true)
                        }

                        override fun onFailure(code: Int, desc: String) = onFinished(false)
                    },
                )
            },
            onRetryScheduled = {
                AtomicAudienceRuntimeRegistry.showMessage(
                    context.getString(R.string.atomic_audience_like_failed),
                )
            },
            onSendException = { error ->
                AtomicAudienceRuntimeRegistry.warning(
                    "AtomicX 点赞发送异常，liveId=$liveId",
                    error,
                )
            },
        )
    }

    fun like() = likeBatcher?.like() ?: Unit

    fun release() {
        if (released) return
        // 离开前尽力提交已经合并的点赞。
        likeBatcher?.release(flushPending = true)
        likeBatcher = null
        released = true
        store?.removeLikeListener(likeListener)
        store = null
        boundLiveId = ""
    }
}
