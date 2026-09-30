package io.github.gycrosskit.livesdk

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveJoinFailureTest {
    @Test
    fun `room does not exist is treated as ended live`() {
        assertTrue(LiveJoinFailure.isEndedLive(100004))
    }

    @Test
    fun `network and permission errors remain join failures`() {
        assertFalse(LiveJoinFailure.isEndedLive(-1))
        assertFalse(LiveJoinFailure.isEndedLive(100005))
    }
}
