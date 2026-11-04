package com.dgtang.live.room

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveOverlayTest {
    private data class Item(
        override val key: String,
        override val priority: LiveOverlayPriority = LiveOverlayPriority.USER,
        override val resumable: Boolean = true,
        override val expiresAtElapsedRealtimeMillis: Long? = null,
    ) : LiveOverlayItem

    @Test fun expiredQueuedItemsNeverResumeAndDuplicateKeysReplace() {
        val first = Item("first", expiresAtElapsedRealtimeMillis = 10)
        val urgent = Item("urgent", LiveOverlayPriority.RESULT, resumable = false)
        var state = LiveOverlayReducer.enqueue(LiveOverlayState(), first, 0)
        state = LiveOverlayReducer.enqueue(state, urgent, 1)
        state = LiveOverlayReducer.enqueue(state, first.copy(expiresAtElapsedRealtimeMillis = 5), 2)
        assertEquals(1, state.pending.size)
        assertNull(LiveOverlayReducer.dismissActive(state, 5).active)
    }

    @Test fun removeMatchingPreservesOtherItemsAndArrivalOrder() {
        var state = LiveOverlayState(active = Item("first"), pending = listOf(Item("second"), Item("third")))
        state = LiveOverlayReducer.removeWhere(state, 0) { it.key == "first" }
        assertEquals("second", state.active?.key)
        assertEquals("third", LiveOverlayReducer.dismissActive(state, 0).active?.key)
    }
}
