package io.github.gycrosskit.livesdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class AtomicAudienceSessionMessages(
    val queueTimeout: String,
    val joinTimeout: String,
    val joinInvocationFailed: String,
)

/** 会话状态机只发稳定事件，不直接修改平台 View、SDK Store 或业务 Compose 状态。 */
internal interface AtomicAudienceSessionEvents {
    fun onJoinStarted()
    fun onJoinSucceeded()
    fun onJoinFailed(code: Int, message: String)
    fun onLiveUnavailable(message: String)
    fun onLiveEnded()
}

/**
 * Android/iOS 共用的 AtomicX 完整观看会话状态机。
 *
 * 它统一处理共享 Store 排队、进离房超时、关播、迟到回调、宿主提前释放和令牌幂等释放。平台层只实现
 * [join] 与 [leave]，并在 SDK 回调到达时调用 `joined`、`joinFailed`、`liveEnded` 等入口。
 */
internal class AtomicAudienceSession(
    private val liveId: String,
    private val messages: AtomicAudienceSessionMessages,
    private val events: AtomicAudienceSessionEvents,
    private val join: () -> Unit,
    private val leave: (onFinished: (succeeded: Boolean, code: Int, message: String) -> Unit) -> Unit,
    private val gate: AtomicAudienceSessionGate = AtomicAudienceSessionCoordinator,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private enum class State { WAITING, JOINING, JOINED, ENDED, LEAVING, RELEASED }

    private val token = Any()
    private var state = State.WAITING
    private var joinCompleted = false
    private var joinedOnce = false
    private var releaseRequested = false
    private var terminalEventDelivered = false
    private var tokenReleased = false
    private var joinTimeoutJob: Job? = null
    private var leaveTimeoutJob: Job? = null

    val isJoined: Boolean
        get() = state == State.JOINED

    init {
        AtomicAudienceRuntimeRegistry.info("等待获取 AtomicX 观看会话，liveId=$liveId")
        gate.acquire(
            token = token,
            liveId = liveId,
            onTimeout = ::queueTimedOut,
            start = ::startJoin,
        )
    }

    fun joined(onReady: () -> Unit = {}) {
        if (state == State.RELEASED || state == State.LEAVING || joinCompleted) return
        joinCompleted = true
        joinedOnce = true
        cancelJoinTimeout()
        if (releaseRequested || state == State.ENDED) {
            startLeave()
            return
        }
        state = State.JOINED
        onReady()
        AtomicAudienceRuntimeRegistry.info("AtomicX 进房成功，liveId=$liveId")
        events.onJoinSucceeded()
    }

    fun joinFailed(code: Int, message: String) {
        if (state == State.RELEASED || state == State.LEAVING || joinCompleted) return
        joinCompleted = true
        cancelJoinTimeout()
        state = State.ENDED
        AtomicAudienceRuntimeRegistry.warning(
            "AtomicX 进房失败，liveId=$liveId, code=$code, message=$message",
        )
        if (!releaseRequested && !terminalEventDelivered) {
            terminalEventDelivered = true
            if (LiveJoinFailure.isEndedLive(code)) {
                events.onLiveUnavailable(message)
            } else {
                events.onJoinFailed(code, message)
            }
        }
        // join 已经交给 SDK，即使失败也先执行幂等 leave/清理原生 View，再放行下一场会话。
        startLeave()
    }

    fun liveEnded(message: String = "") {
        if (releaseRequested || state == State.LEAVING || state == State.RELEASED || terminalEventDelivered) return
        terminalEventDelivered = true
        state = State.ENDED
        if (joinCompleted && joinedOnce) {
            AtomicAudienceRuntimeRegistry.info("观看中的 AtomicX 直播已结束，liveId=$liveId")
            events.onLiveEnded()
            startLeave()
        } else {
            AtomicAudienceRuntimeRegistry.info("AtomicX 直播进房前已结束，liveId=$liveId")
            events.onLiveUnavailable(message)
            if (joinCompleted) startLeave()
        }
    }

    fun liveUnavailable(message: String) {
        if (releaseRequested || state == State.LEAVING || state == State.RELEASED || terminalEventDelivered) return
        terminalEventDelivered = true
        state = State.ENDED
        AtomicAudienceRuntimeRegistry.warning(
            "AtomicX 直播不可用，liveId=$liveId, message=$message",
        )
        events.onLiveUnavailable(message)
        if (joinCompleted) startLeave()
    }

    /** 宿主释放可重复调用；若 join 尚未回调，等待回调或超时后再离房，避免 leave/join 交叉。 */
    fun release() {
        if (releaseRequested || tokenReleased) return
        releaseRequested = true
        AtomicAudienceRuntimeRegistry.info("宿主请求释放 AtomicX 会话，liveId=$liveId, state=$state")
        when (state) {
            State.WAITING -> releaseToken()
            State.JOINING -> if (joinCompleted) startLeave()
            State.JOINED -> startLeave()
            State.ENDED -> if (joinCompleted || joinedOnce) startLeave()
            State.LEAVING, State.RELEASED -> Unit
        }
    }

    private fun startJoin() {
        if (releaseRequested || terminalEventDelivered || tokenReleased) {
            releaseToken()
            return
        }
        state = State.JOINING
        AtomicLivePreviewRuntime.stopActivePreview()
        AtomicAudienceRuntimeRegistry.info("开始进入 AtomicX 直播间，liveId=$liveId")
        events.onJoinStarted()
        joinTimeoutJob = scope.launch {
            delay(ATOMIC_JOIN_TIMEOUT_MILLIS)
            joinTimedOut()
        }
        try {
            join()
        } catch (error: Throwable) {
            AtomicAudienceRuntimeRegistry.error("AtomicX 进房调用异常，liveId=$liveId", error)
            if (!releaseRequested && !terminalEventDelivered) {
                terminalEventDelivered = true
                events.onJoinFailed(ERROR_ATOMIC_JOIN_INVOCATION, messages.joinInvocationFailed)
            }
            joinCompleted = true
            state = State.ENDED
            startLeave()
        }
    }

    private fun startLeave() {
        if (tokenReleased || state == State.LEAVING) return
        cancelJoinTimeout()
        state = State.LEAVING
        AtomicAudienceRuntimeRegistry.info("开始清理 AtomicX 观看会话，liveId=$liveId")
        leaveTimeoutJob = scope.launch {
            delay(ATOMIC_LEAVE_TIMEOUT_MILLIS)
            AtomicAudienceRuntimeRegistry.warning(
                "AtomicX 离房超时，强制放行观看会话队列，liveId=$liveId",
            )
            releaseToken()
        }
        try {
            leave { succeeded, code, message ->
                if (succeeded) {
                    AtomicAudienceRuntimeRegistry.info("AtomicX 离房成功，liveId=$liveId")
                } else {
                    AtomicAudienceRuntimeRegistry.warning(
                        "AtomicX 离房失败但继续释放会话，" +
                            "liveId=$liveId, code=$code, message=$message",
                    )
                }
                releaseToken()
            }
        } catch (error: Throwable) {
            AtomicAudienceRuntimeRegistry.warning(
                "AtomicX 离房调用异常但继续释放会话，liveId=$liveId",
                error,
            )
            releaseToken()
        }
    }

    private fun queueTimedOut() {
        if (state != State.WAITING || releaseRequested) return
        terminalEventDelivered = true
        state = State.RELEASED
        events.onJoinFailed(ERROR_ATOMIC_SESSION_QUEUE_TIMEOUT, messages.queueTimeout)
        releaseToken()
    }

    private fun joinTimedOut() {
        // 关播/不可用事件会先进入 ENDED，但在途 join 仍必须在期限内完成清理。
        if ((state != State.JOINING && state != State.ENDED) || joinCompleted) return
        AtomicAudienceRuntimeRegistry.warning("AtomicX 进房超时，开始强制离房，liveId=$liveId")
        if (!releaseRequested && !terminalEventDelivered) {
            terminalEventDelivered = true
            events.onJoinFailed(ERROR_ATOMIC_JOIN_TIMEOUT, messages.joinTimeout)
        }
        joinCompleted = true
        state = State.ENDED
        startLeave()
    }

    private fun releaseToken() {
        if (tokenReleased) return
        tokenReleased = true
        state = State.RELEASED
        cancelJoinTimeout()
        leaveTimeoutJob?.cancel()
        leaveTimeoutJob = null
        gate.release(token)
        scope.cancel()
    }

    private fun cancelJoinTimeout() {
        joinTimeoutJob?.cancel()
        joinTimeoutJob = null
    }
}
