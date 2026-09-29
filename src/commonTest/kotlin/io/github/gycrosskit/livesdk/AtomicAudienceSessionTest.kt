package io.github.gycrosskit.livesdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class AtomicAudienceSessionTest {
    @Test
    fun `terminal callback during join still times out and releases the gate`() = runTest {
        for (ended in listOf(true, false)) {
            val gate = ImmediateGate()
            val events = RecordingEvents()
            var leaveCount = 0
            val session = AtomicAudienceSession(
                liveId = "live-terminal",
                messages = AtomicAudienceSessionMessages("queue", "join", "invoke"),
                events = events,
                join = {},
                leave = { leaveCount++ },
                gate = gate,
                scope = CoroutineScope(coroutineContext + SupervisorJob()),
            )
            runCurrent()
            if (ended) session.liveEnded() else session.liveUnavailable("unavailable")
            session.release()
            advanceTimeBy(ATOMIC_JOIN_TIMEOUT_MILLIS)
            runCurrent()
            assertEquals(1, leaveCount)
            // 清理开始后的迟到 join 不能恢复观看或重新开启一次 leave。
            session.joined()
            assertEquals(0, events.joinSucceeded)
            advanceTimeBy(ATOMIC_LEAVE_TIMEOUT_MILLIS)
            runCurrent()
            assertEquals(1, gate.releaseCount)
        }
    }

    @Test
    fun `release during join waits for sdk callback before leaving`() {
        val gate = ImmediateGate()
        val events = RecordingEvents()
        var leaveCount = 0
        var leaveFinished: ((Boolean, Int, String) -> Unit)? = null
        val session = session(
            gate = gate,
            events = events,
            leave = { callback ->
                leaveCount += 1
                leaveFinished = callback
            },
        )

        session.release()
        assertEquals(0, leaveCount)

        session.joined()
        assertEquals(1, leaveCount)
        assertEquals(0, events.joinSucceeded)

        leaveFinished?.invoke(true, 0, "")
        assertEquals(1, gate.releaseCount)
    }

    @Test
    fun `live ended is delivered once and late callbacks are ignored`() {
        val gate = ImmediateGate()
        val events = RecordingEvents()
        var leaveFinished: ((Boolean, Int, String) -> Unit)? = null
        val session = session(
            gate = gate,
            events = events,
            leave = { callback -> leaveFinished = callback },
        )
        session.joined()

        session.liveEnded("ended")
        session.liveEnded("duplicate")
        session.joinFailed(10, "late")

        assertEquals(1, events.joinSucceeded)
        assertEquals(1, events.liveEnded)
        assertEquals(0, events.joinFailed)
        leaveFinished?.invoke(true, 0, "")
        assertEquals(1, gate.releaseCount)
    }

    @Test
    fun `join failure releases gate once and ignores late success`() {
        val gate = ImmediateGate()
        val events = RecordingEvents()
        var leaveFinished: ((Boolean, Int, String) -> Unit)? = null
        val session = session(
            gate = gate,
            events = events,
            leave = { callback -> leaveFinished = callback },
        )

        session.joinFailed(7, "failed")
        session.joined()
        leaveFinished?.invoke(false, 8, "leave failed")

        assertEquals(1, events.joinFailed)
        assertEquals(0, events.joinSucceeded)
        assertEquals(1, gate.releaseCount)
    }

    private fun session(
        gate: AtomicAudienceSessionGate,
        events: AtomicAudienceSessionEvents,
        leave: ((Boolean, Int, String) -> Unit) -> Unit,
    ) = AtomicAudienceSession(
        liveId = "live-1",
        messages = AtomicAudienceSessionMessages("queue", "join", "invoke"),
        events = events,
        join = {},
        leave = leave,
        gate = gate,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    private class ImmediateGate : AtomicAudienceSessionGate {
        var releaseCount = 0

        override fun acquire(
            token: Any,
            liveId: String,
            onTimeout: () -> Unit,
            start: () -> Unit,
        ) = start()

        override fun release(token: Any) {
            releaseCount += 1
        }
    }

    private class RecordingEvents : AtomicAudienceSessionEvents {
        var joinSucceeded = 0
        var joinFailed = 0
        var liveEnded = 0

        override fun onJoinStarted() = Unit
        override fun onJoinSucceeded() {
            joinSucceeded += 1
        }

        override fun onJoinFailed(code: Int, message: String) {
            joinFailed += 1
        }

        override fun onLiveUnavailable(message: String) = Unit
        override fun onLiveEnded() {
            liveEnded += 1
        }
    }
}
