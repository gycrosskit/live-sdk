package io.github.gycrosskit.livesdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class LiveLikeBatcherTest {
    @Test
    fun `first like sends immediately and rapid likes are merged`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val sent = mutableListOf<Int>()
        val batcher = LiveLikeBatcher(send = { count, callback -> sent += count; callback(true) })
        try {
            batcher.like()
            repeat(3) { batcher.like() }
            assertEquals(listOf(1), sent)
            advanceUntilIdle()
            assertEquals(listOf(1, 3), sent)
        } finally {
            batcher.release(flushPending = false)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `failed batch is restored with pending clicks and retry stops after release`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val sent = mutableListOf<Int>()
        val callbacks = mutableListOf<(Boolean) -> Unit>()
        var retries = 0
        val batcher = LiveLikeBatcher(
            send = { count, callback -> sent += count; callbacks += callback },
            onRetryScheduled = { retries++ },
        )
        try {
            batcher.like()
            batcher.like()
            callbacks.first()(false)
            runCurrent()
            assertEquals(1, retries)
            advanceUntilIdle()
            assertEquals(listOf(1, 2), sent)
            batcher.like()
            batcher.release()
            batcher.release()
            batcher.like()
            callbacks[1](false)
            callbacks[2](false)
            advanceUntilIdle()
            assertEquals(listOf(1, 2, 1), sent)
            assertEquals(1, retries)
        } finally {
            batcher.release(flushPending = false)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `synchronous send failure can be retried without losing the count`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val sent = mutableListOf<Int>()
        var failures = 0
        val batcher = LiveLikeBatcher(
            send = { count, callback ->
                if (failures == 0) error("SDK invocation failed")
                sent += count
                callback(true)
            },
            onSendException = { failures++ },
        )
        try {
            batcher.like()
            advanceUntilIdle()
            assertEquals(1, failures)
            assertEquals(listOf(1), sent)
        } finally {
            batcher.release(flushPending = false)
            Dispatchers.resetMain()
        }
    }
}
