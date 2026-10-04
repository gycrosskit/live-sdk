package io.github.gycrosskit.livesdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

class LiveAudienceSnapshotStoreTest {
    @Test
    fun `two renderers observe the same native snapshot flow`() = runTest {
        val store = LiveAudienceSnapshotStore("live-1")
        val first = mutableListOf<LiveAudienceContentSnapshot>()
        val second = mutableListOf<LiveAudienceContentSnapshot>()
        val firstJob = launch(start = CoroutineStart.UNDISPATCHED) { store.snapshots.take(3).toList(first) }
        val secondJob = launch(start = CoroutineStart.UNDISPATCHED) { store.snapshots.take(3).toList(second) }
        store.updateLoading(false)
        yield()
        store.updateIntroduction("notice")
        firstJob.join()
        secondJob.join()
        assertEquals(first, second)
        assertFalse(first[1].loading)
        assertEquals("notice", first.last().introduction)
    }

    @Test
    fun `message identity does not collide when payload contains separators`() {
        val store = LiveAudienceSnapshotStore("live-1")
        val first = message(content = "x|y", businessId = "", data = "z")
        val second = message(content = "x", businessId = "y|", data = "z")

        assertTrue(store.appendMessage(first))
        assertTrue(store.appendMessage(second))
        assertEquals(listOf(first, second), store.snapshot().messages)
    }

    @Test
    fun `batch append publishes only new messages`() {
        val store = LiveAudienceSnapshotStore("live-1")
        val first = message(sequence = 1)
        val second = message(sequence = 2)

        assertEquals(2, store.appendMessages(listOf(first, second, first)))
        assertEquals(0, store.appendMessages(listOf(first, second)))
        assertEquals(listOf(first, second), store.snapshot().messages)
    }

    @Test
    fun `message list stays bounded and evicted identity can be received again`() {
        val store = LiveAudienceSnapshotStore("live-1")
        val first = message(sequence = 0)

        repeat(201) { sequence ->
            assertTrue(store.appendMessage(message(sequence = sequence.toLong())))
        }

        assertEquals(200, store.snapshot().messages.size)
        assertFalse(first in store.snapshot().messages)
        assertTrue(store.appendMessage(first))
        assertEquals(200, store.snapshot().messages.size)
        assertEquals(first, store.snapshot().messages.last())
    }

    @Test
    fun `audience count never falls below materialized users`() {
        val store = LiveAudienceSnapshotStore("live-1")
        val users = listOf(
            LiveAudienceUserSnapshot("user-1", "A", ""),
            LiveAudienceUserSnapshot("user-2", "B", ""),
        )

        store.updateAudience(users, count = 1)

        assertEquals(users, store.snapshot().audience)
        assertEquals(2, store.snapshot().audienceCount)
    }

    @Test
    fun `previous snapshot is not mutated by later sdk callbacks`() {
        val store = LiveAudienceSnapshotStore("live-1")
        val mutableAudience = mutableListOf(LiveAudienceUserSnapshot("user-1", "A", ""))
        store.appendMessage(message(sequence = 1))
        store.updateAudience(mutableAudience, count = 1)
        val previous = store.snapshot()

        store.appendMessage(message(sequence = 2))
        mutableAudience += LiveAudienceUserSnapshot("user-2", "B", "")

        assertEquals(1, previous.messages.size)
        assertEquals(1, previous.audience.size)
    }

    @Test
    fun `non message updates reuse immutable message snapshot`() {
        val store = LiveAudienceSnapshotStore("live-1")
        store.appendMessage(message(sequence = 1))
        val messages = store.snapshot().messages

        store.updateLoading(false)
        store.updateInteractionReady(true)

        assertSame(messages, store.snapshot().messages)
        store.appendMessage(message(sequence = 2))
        assertNotSame(messages, store.snapshot().messages)
    }

    @Test
    fun `interleaved callbacks preserve the other snapshot fields`() {
        val store = LiveAudienceSnapshotStore("live-1", "initial")
        val owner = LiveAudienceUserSnapshot("host", "Host", "avatar")
        store.updateHost(store.snapshot().host.copy(owner = owner))
        store.updateFollowState(true, true, false, 12)
        store.appendMemberMessage(owner, joined = true, timestampSeconds = 2.0)
        val previous = store.snapshot()
        store.updateIntroduction("notice")
        store.updateAudience(listOf(owner), count = 4)
        store.updateLoading(false)
        store.updateInteractionReady(true)
        store.updatePictureInPicture(true)
        store.emitLikeEffect()
        store.emitLikeEffect()
        store.appendMemberMessage(owner, joined = false, timestampSeconds = 3.0)

        val current = store.snapshot()
        assertEquals(previous.host, current.host)
        assertEquals("notice", current.introduction)
        assertEquals(4, current.audienceCount)
        assertFalse(current.loading)
        assertTrue(current.interactionReady && current.pictureInPicture)
        assertEquals(2L, current.likeEffectSequence)
        assertEquals(listOf(-1L, -2L), current.messages.map { it.sequence })
        assertEquals("initial", previous.introduction)
        assertEquals(1, previous.messages.size)
    }

    private fun message(
        sequence: Long = 1,
        content: String = "content",
        businessId: String = "",
        data: String = "",
    ) = LiveAudienceMessageSnapshot(
        sequence = sequence,
        timestampSeconds = 1.0,
        senderId = "sender",
        senderName = "Sender",
        senderAvatarUrl = "",
        content = content,
        businessId = businessId,
        data = data,
    )
}
