package io.github.gycrosskit.livesdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AtomicLivePreviewControllerTest {
    @Test
    fun `background awaited stop detaches before the next sdk operation`() {
        val mainScheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(mainScheduler))
        val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val playback = FakePlayback()
        val controller = AtomicLivePreviewController("live-await", playback, onStateChanged = {})
        try {
            controller.setActive(true)
            controller.setLifecycleStarted(true)
            val stop = backgroundScope.async {
                AtomicLivePreviewRuntime.stopActivePreviewAndAwait()
                // 模拟紧接着的登录/进房；未 detach 不能放行后续 SDK 操作。
                assertEquals(1, playback.detachCount)
            }
            assertFalse(stop.isCompleted)
            assertEquals(0, playback.detachCount)

            mainScheduler.runCurrent()
            assertTrue(stop.isCompleted)
            stop.getCompleted()
        } finally {
            controller.release()
            backgroundScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `full session detaches preview once before late lifecycle disposal`() {
        val playback = FakePlayback()
        val states = mutableListOf<LivePreviewState>()
        val controller = AtomicLivePreviewController("live-1", playback, states::add)

        controller.setLifecycleStarted(true)
        controller.setActive(true)
        AtomicLivePreviewRuntime.stopActivePreview()
        assertEquals(1, playback.detachCount)
        controller.setLifecycleStarted(false)
        controller.release()

        assertEquals(1, playback.detachCount)
        assertEquals(LivePreviewState.COVER, states.last())
    }

    @Test
    fun `preview waits for full session release before restarting`() {
        val playback = FakePlayback()
        val states = mutableListOf<LivePreviewState>()
        val gate = DeferredStartGate()
        val controller = AtomicLivePreviewController(
            liveId = "live-1",
            playback = playback,
            onStateChanged = states::add,
            startGate = gate,
        )

        controller.setActive(true)
        controller.setLifecycleStarted(true)
        assertEquals(0, playback.startCount)

        gate.release()

        assertEquals(1, playback.startCount)
        assertEquals(LivePreviewState.LOADING, states.last())
        controller.release()
    }

    @Test
    fun `active and started are both required before preview starts`() {
        val playback = FakePlayback()
        val states = mutableListOf<LivePreviewState>()
        val controller = AtomicLivePreviewController("live-1", playback, states::add)

        controller.setActive(true)
        assertEquals(0, playback.startCount)

        controller.setLifecycleStarted(true)

        assertEquals(1, playback.startCount)
        assertEquals(listOf(LivePreviewState.LOADING), states)
        playback.callback.onPlaying()
        assertEquals(LivePreviewState.PLAYING, states.last())
        controller.release()
    }

    @Test
    fun `inactive and lifecycle stop release preview once and return cover`() {
        val playback = FakePlayback()
        val states = mutableListOf<LivePreviewState>()
        val controller = AtomicLivePreviewController("live-1", playback, states::add)
        controller.setActive(true)
        controller.setLifecycleStarted(true)
        playback.callback.onPlaying()

        controller.setActive(false)
        controller.setActive(false)
        controller.setLifecycleStarted(false)

        assertEquals(1, playback.detachCount)
        assertEquals(LivePreviewState.COVER, states.last())
        controller.release()
    }

    @Test
    fun `release is idempotent and ignores late sdk callback`() {
        val playback = FakePlayback()
        val states = mutableListOf<LivePreviewState>()
        val controller = AtomicLivePreviewController("live-1", playback, states::add)
        controller.setActive(true)
        controller.setLifecycleStarted(true)
        val staleCallback = playback.callback

        controller.release()
        controller.release()
        staleCallback.onPlaying()
        staleCallback.onFailure(1, "late")

        assertEquals(1, playback.detachCount)
        assertEquals(LivePreviewState.COVER, states.last())
    }

    @Test
    fun `failure stops sdk preview and remains failed until lifecycle retry`() {
        val playback = FakePlayback()
        val states = mutableListOf<LivePreviewState>()
        val controller = AtomicLivePreviewController("live-1", playback, states::add)
        controller.setActive(true)
        controller.setLifecycleStarted(true)

        playback.callback.onFailure(2, "failed")

        assertEquals(1, playback.detachCount)
        assertEquals(LivePreviewState.FAILED, states.last())

        controller.setLifecycleStarted(false)
        controller.setLifecycleStarted(true)
        assertEquals(2, playback.startCount)
        assertEquals(LivePreviewState.LOADING, states.last())
        controller.release()
    }

    private class FakePlayback : AtomicLivePreviewPlayback {
        var startCount = 0
        var detachCount = 0
        lateinit var callback: AtomicLivePreviewPlayback.Callback

        override fun start(liveId: String, callback: AtomicLivePreviewPlayback.Callback) {
            startCount += 1
            this.callback = callback
        }

        override fun stopAndDetach(liveId: String) {
            detachCount += 1
        }
    }

    private class DeferredStartGate : AtomicLivePreviewStartGate {
        private var pending: (() -> Unit)? = null

        override fun request(token: Any, start: () -> Unit) {
            pending = start
        }

        override fun cancel(token: Any) {
            pending = null
        }

        fun release() {
            pending?.also { pending = null }?.invoke()
        }
    }
}
