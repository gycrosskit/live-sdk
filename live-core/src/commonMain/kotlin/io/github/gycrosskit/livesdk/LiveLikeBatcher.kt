package io.github.gycrosskit.livesdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 双端共用的本地点赞合并策略。
 *
 * 首次点赞立即发送，后续连点在固定窗口合并；失败批次只在当前会话未释放时恢复。平台层
 * 只实现 SDK `sendLike` 调用，不再各自维护节流时序。
 */
internal class LiveLikeBatcher(
    private val send: (count: Int, onFinished: (Boolean) -> Unit) -> Unit,
    private val onRetryScheduled: () -> Unit = {},
    private val onSendException: (Throwable) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingCount = 0
    private var lastSentMark: TimeMark? = null
    private var scheduledJob: Job? = null
    private var released = false

    fun like() {
        if (released) return
        pendingCount++
        val elapsedMillis = lastSentMark?.elapsedNow()?.inWholeMilliseconds
        if (elapsedMillis == null || elapsedMillis >= SEND_INTERVAL_MILLIS) {
            scheduledJob?.cancel()
            scheduledJob = null
            flush(retryOnFailure = true)
        } else if (scheduledJob == null) {
            schedule(SEND_INTERVAL_MILLIS - elapsedMillis)
        }
    }

    fun release(flushPending: Boolean = true) {
        if (released) return
        scheduledJob?.cancel()
        scheduledJob = null
        if (flushPending) flush(retryOnFailure = false)
        released = true
        pendingCount = 0
        scope.cancel()
    }

    private fun flush(retryOnFailure: Boolean) {
        val count = pendingCount
        if (count <= 0) return
        pendingCount = 0
        lastSentMark = TimeSource.Monotonic.markNow()
        try {
            send(count) { succeeded ->
                if (!succeeded && retryOnFailure) restoreFailedBatch(count)
            }
        } catch (error: Throwable) {
            onSendException(error)
            if (retryOnFailure) restoreFailedBatch(count)
        }
    }

    private fun restoreFailedBatch(count: Int) {
        scope.launch {
            if (released) return@launch
            pendingCount += count
            onRetryScheduled()
            if (scheduledJob == null) schedule(SEND_INTERVAL_MILLIS)
        }
    }

    private fun schedule(delayMillis: Long) {
        scheduledJob = scope.launch {
            delay(delayMillis.coerceAtLeast(0L))
            scheduledJob = null
            flush(retryOnFailure = true)
        }
    }
}

private const val SEND_INTERVAL_MILLIS = 6_000L
