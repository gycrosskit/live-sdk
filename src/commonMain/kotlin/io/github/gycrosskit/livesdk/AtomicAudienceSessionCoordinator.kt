package io.github.gycrosskit.livesdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 完整观看会话申请共享 AtomicX Store 的最小门禁，便于独立验证进离房竞态。 */
internal interface AtomicAudienceSessionGate {
    fun acquire(
        token: Any,
        liveId: String,
        onTimeout: () -> Unit,
        start: () -> Unit,
    )

    fun release(token: Any)
}

/**
 * 串行管理进程级 AtomicX 完整观看会话，并为列表预览提供空闲门禁。
 *
 * Android 与 iOS 的 AtomicX 都暴露共享 Store。快速退出并进入另一房间时，旧房间的迟到回调或
 * `leaveLive` 可能误伤新房间与列表预览。本协调器只负责跨端一致的排队、超时和令牌规则，不持有
 * Activity、UIView、SDK Store 或业务状态。
 */
internal object AtomicAudienceSessionCoordinator : AtomicAudienceSessionGate {
    private data class PendingSession(
        val token: Any,
        val liveId: String,
        val start: () -> Unit,
        val timeoutJob: Job,
    )

    private data class PendingPreview(
        val token: Any,
        val start: () -> Unit,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pending = ArrayDeque<PendingSession>()
    private var activeToken: Any? = null
    private var pendingPreview: PendingPreview? = null

    /** 获取完整观看会话；已有房间占用时按请求顺序排队，并为每个等待项安装独立超时。 */
    override fun acquire(
        token: Any,
        liveId: String,
        onTimeout: () -> Unit,
        start: () -> Unit,
    ) = onMain {
        if (activeToken == null) {
            activeToken = token
            AtomicAudienceRuntimeRegistry.info("AtomicX 观看会话已获得共享 Store，liveId=$liveId")
            start()
        } else if (activeToken !== token && pending.none { it.token === token }) {
            val timeoutJob = scope.launch {
                delay(ATOMIC_SESSION_QUEUE_TIMEOUT_MILLIS)
                if (removePending(token) != null) {
                    AtomicAudienceRuntimeRegistry.warning("AtomicX 观看会话排队超时，liveId=$liveId")
                    onTimeout()
                }
            }
            pending.addLast(PendingSession(token, liveId, start, timeoutJob))
            AtomicAudienceRuntimeRegistry.info(
                "AtomicX 观看会话进入共享 Store 队列，liveId=$liveId, waiting=${pending.size}",
            )
        }
    }

    /** 释放活动或等待中的会话；只有活动会话释放后才会启动下一个房间或最新列表预览。 */
    override fun release(token: Any) = onMain {
        if (activeToken !== token) {
            removePending(token)
            return@onMain
        }
        activeToken = null
        val next = pending.removeFirstOrNull()
        if (next != null) {
            next.timeoutJob.cancel()
            activeToken = next.token
            AtomicAudienceRuntimeRegistry.info(
                "AtomicX 观看会话从共享 Store 队列启动，liveId=${next.liveId}, waiting=${pending.size}",
            )
            next.start()
        } else {
            pendingPreview?.also { preview ->
                pendingPreview = null
                AtomicAudienceRuntimeRegistry.info("AtomicX 完整会话释放，恢复列表预览")
                preview.start()
            }
        }
    }

    /** 完整观看会话空闲时启动预览，否则只保留当前页面最新一次等待。 */
    fun acquirePreview(token: Any, start: () -> Unit) = onMain {
        if (activeToken == null) {
            pendingPreview = null
            start()
        } else {
            pendingPreview = PendingPreview(token, start)
            AtomicAudienceRuntimeRegistry.info("AtomicX 列表预览等待完整会话释放")
        }
    }

    /** 页面失活或销毁时移除尚未启动的预览，避免离房后恢复旧卡片。 */
    fun cancelPreview(token: Any) = onMain {
        if (pendingPreview?.token === token) pendingPreview = null
    }

    private fun removePending(token: Any): PendingSession? {
        val waiting = pending.firstOrNull { it.token === token } ?: return null
        pending.remove(waiting)
        waiting.timeoutJob.cancel()
        return waiting
    }

    private fun onMain(block: () -> Unit) {
        scope.launch { block() }
    }
}

internal const val ATOMIC_SESSION_QUEUE_TIMEOUT_MILLIS = 15_000L
internal const val ATOMIC_JOIN_TIMEOUT_MILLIS = 20_000L
internal const val ATOMIC_LEAVE_TIMEOUT_MILLIS = 8_000L
internal const val ERROR_ATOMIC_SESSION_QUEUE_TIMEOUT = -20_001
internal const val ERROR_ATOMIC_JOIN_TIMEOUT = -20_002
internal const val ERROR_ATOMIC_JOIN_INVOCATION = -20_003
