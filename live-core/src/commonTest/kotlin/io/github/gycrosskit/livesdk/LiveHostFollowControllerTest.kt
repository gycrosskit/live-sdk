package io.github.gycrosskit.livesdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LiveHostFollowControllerTest {
    @Test
    fun `own account never exposes follow action or starts requests`() = runTest {
        val gateway = FakeGateway(currentUserId = "self")
        val states = mutableListOf<FollowState>()
        val controller = controller(gateway, states)

        controller.bind("self")

        assertEquals(FollowState(), states.last())
        assertEquals(0, gateway.checkFollowedRequests)
        assertEquals(0, gateway.fetchFansRequests)
    }

    @Test
    fun `follow success publishes running result and refreshed fans`() = runTest {
        val gateway = FakeGateway()
        val states = mutableListOf<FollowState>()
        val controller = controller(gateway, states)
        controller.bind("owner")
        gateway.completeCheckFollowed(false)
        gateway.completeFans(10)

        controller.toggleFollow()

        assertTrue(states.last().requestRunning)
        gateway.completeUpdateFollow()
        assertTrue(states.last().followed)
        assertFalse(states.last().requestRunning)
        assertEquals(2, gateway.fetchFansRequests)
        gateway.completeFans(11)
        assertEquals(11, states.last().fansCount)
    }

    @Test
    fun `late callback from previous owner cannot overwrite current state`() = runTest {
        val gateway = FakeGateway()
        val states = mutableListOf<FollowState>()
        val controller = controller(gateway, states)
        controller.bind("owner-1")
        val staleCheck = gateway.checkFollowedCallbacks.last()
        val staleFans = gateway.fetchFansCallbacks.last()

        controller.bind("owner-2")
        staleCheck(LiveFollowResult.Success(true))
        staleFans(LiveFollowResult.Success(99))

        assertEquals(FollowState(visible = true), states.last())
    }

    @Test
    fun `late initial follow check cannot overwrite successful user update`() = runTest {
        val gateway = FakeGateway()
        val states = mutableListOf<FollowState>()
        val controller = controller(gateway, states)
        controller.bind("owner")
        val initialCheck = gateway.checkFollowedCallbacks.single()

        controller.toggleFollow()
        gateway.completeUpdateFollow()
        initialCheck(LiveFollowResult.Success(false))

        assertTrue(states.last().followed)
        assertFalse(states.last().requestRunning)
    }

    @Test
    fun `older fans callback cannot overwrite refreshed count`() = runTest {
        val gateway = FakeGateway()
        val states = mutableListOf<FollowState>()
        val controller = controller(gateway, states)
        controller.bind("owner")
        val initialFans = gateway.fetchFansCallbacks.single()

        controller.toggleFollow()
        gateway.completeUpdateFollow()
        val refreshedFans = gateway.fetchFansCallbacks.last()
        refreshedFans(LiveFollowResult.Success(11))
        initialFans(LiveFollowResult.Success(10))

        assertEquals(11, states.last().fansCount)
    }

    @Test
    fun `same owner bind is idempotent`() = runTest {
        val gateway = FakeGateway()
        val states = mutableListOf<FollowState>()
        val controller = controller(gateway, states)
        controller.bind("owner")
        gateway.completeCheckFollowed(true)
        gateway.completeFans(10)

        controller.bind("owner")

        assertEquals(FollowState(visible = true, followed = true, fansCount = 10), states.last())
        assertEquals(1, gateway.checkFollowedRequests)
        assertEquals(1, gateway.fetchFansRequests)
    }

    @Test
    fun `old callback stays invalid when owner id cycles back`() = runTest {
        val gateway = FakeGateway()
        val states = mutableListOf<FollowState>()
        val controller = controller(gateway, states)
        controller.bind("owner-1")
        val firstVisitCheck = gateway.checkFollowedCallbacks.last()

        controller.bind("owner-2")
        controller.bind("owner-1")
        firstVisitCheck(LiveFollowResult.Success(true))

        assertEquals(FollowState(visible = true), states.last())
    }

    @Test
    fun `missing callback unlocks retry of same target and ignores late result`() = runTest {
        val gateway = FakeGateway()
        val states = mutableListOf<FollowState>()
        val controller = controller(gateway, states)
        controller.bind("owner")
        gateway.completeCheckFollowed(false)
        controller.toggleFollow()
        runCurrent()
        advanceTimeBy(10_001)
        runCurrent()
        assertFalse(states.last().requestRunning)
        assertFalse(states.last().followed)

        controller.toggleFollow()
        assertEquals(listOf(true, true), gateway.updateFollowTargets)
        gateway.completeUpdateFollow()
        assertTrue(states.last().requestRunning)
        assertFalse(states.last().followed)
        gateway.completeUpdateFollow()
        assertFalse(states.last().requestRunning)
        assertTrue(states.last().followed)
    }

    @Test
    fun `release cancels pending follow timeout and rejects callback`() = runTest {
        val gateway = FakeGateway()
        val states = mutableListOf<FollowState>()
        val controller = controller(gateway, states)
        controller.bind("owner")
        controller.toggleFollow()
        controller.release()
        val countAfterRelease = states.size
        advanceTimeBy(10_001)
        runCurrent()
        gateway.completeUpdateFollow()
        assertEquals(countAfterRelease, states.size)
        assertEquals(FollowState(), states.last())
    }

    private fun TestScope.controller(
        gateway: FakeGateway,
        states: MutableList<FollowState>,
    ) = LiveHostFollowController(
        gateway = gateway,
        scope = backgroundScope,
        onStateChanged = { visible, followed, requestRunning, fansCount ->
            states += FollowState(visible, followed, requestRunning, fansCount)
        },
    )

    private data class FollowState(
        val visible: Boolean = false,
        val followed: Boolean = false,
        val requestRunning: Boolean = false,
        val fansCount: Long = 0,
    )

    private class FakeGateway(
        override val currentUserId: String = "self",
    ) : LiveHostFollowGateway {
        val checkFollowedCallbacks = mutableListOf<(LiveFollowResult<Boolean>) -> Unit>()
        val fetchFansCallbacks = mutableListOf<(LiveFollowResult<Long>) -> Unit>()
        private val updateFollowCallbacks = mutableListOf<(LiveFollowResult<Unit>) -> Unit>()
        val updateFollowTargets = mutableListOf<Boolean>()

        var checkFollowedRequests = 0
            private set
        var fetchFansRequests = 0
            private set

        override fun checkFollowed(
            userId: String,
            callback: (LiveFollowResult<Boolean>) -> Unit,
        ) {
            checkFollowedRequests += 1
            checkFollowedCallbacks += callback
        }

        override fun updateFollow(
            userId: String,
            followed: Boolean,
            callback: (LiveFollowResult<Unit>) -> Unit,
        ) {
            updateFollowTargets += followed
            updateFollowCallbacks += callback
        }

        override fun fetchFans(
            userId: String,
            callback: (LiveFollowResult<Long>) -> Unit,
        ) {
            fetchFansRequests += 1
            fetchFansCallbacks += callback
        }

        fun completeCheckFollowed(followed: Boolean) {
            checkFollowedCallbacks.removeAt(0)(LiveFollowResult.Success(followed))
        }

        fun completeUpdateFollow() {
            updateFollowCallbacks.removeAt(0)(LiveFollowResult.Success(Unit))
        }

        fun completeFans(count: Long) {
            fetchFansCallbacks.removeAt(0)(LiveFollowResult.Success(count))
        }
    }
}
