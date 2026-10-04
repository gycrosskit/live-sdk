package io.github.gycrosskit.livesdk
import kotlin.test.*
class LiveAccountOwnershipTest {
    @Test fun ownBorrowForeignAndToolkitAppIdHaveSeparateCleanupAuthority() {
        val target = LiveSdkIdentity(100, "member")
        assertEquals(LiveAccountAction.INITIALIZE, liveAccountPreparationAction(null, false, "member", target, false))
        assertFalse(liveOwnsActualIdentity(target, false, "member"))
        assertEquals(LiveAccountAction.REUSE, liveAccountPreparationAction(target, false, "member", target, true))
        assertEquals(LiveAccountAction.REJECT, liveAccountPreparationAction(target, false, "member", target.copy(userId = "next"), true))
        assertEquals(LiveAccountAction.RESET, liveAccountPreparationAction(target, true, "member", target.copy(userId = "next"), true))
        assertEquals(LiveAccountAction.REJECT, liveAccountPreparationAction(target, true, "foreign", target, true))
        assertFalse(liveOwnsActualIdentity(target, true, "foreign"))
        assertEquals(LiveAccountAction.REJECT, liveAccountPreparationAction(null, false, "member", target, false, 101))
        assertEquals(LiveAccountAction.INITIALIZE, liveAccountPreparationAction(null, false, null, target, false, 101))
        assertFalse(liveOwnsActualIdentity(target, true, "member", 101))
        assertEquals(LiveAccountAction.REJECT, liveAccountPreparationAction(target, true, "member", target, true, 101))
        assertEquals(LiveAccountAction.REJECT, liveAccountPreparationAction(target.copy(userId = "previous"), true, "member", target, false, 101))
    }
    @Test fun lateCallbackCannotClaimForeignIdentityAndResetMustRecheckActual() {
        val target = LiveSdkIdentity(100, "first")
        val next = target.copy(userId = "next")
        assertEquals(LiveAccountAction.RESET, liveAccountPreparationAction(target, true, "first", next, true))
        // old logout pending -> other component establishes foreign user -> callback must not login next.
        assertEquals(LiveAccountAction.REJECT, liveAccountPreparationAction(null, false, "foreign", next, false))
        assertFalse(liveOwnsActualIdentity(next, true, "foreign"))
        val observer = object : LiveImObserver {
            override fun onRestCustomData(groupId: String, payload: String) = Unit
            override fun onCurrentUserRemoved(groupId: String, operatorUserId: String) = Unit
            override fun onGroupDismissed(groupId: String) = Unit
            override fun onKickedOffline() = Unit
            override fun onUserSigExpired() = Unit
        }
        val gate = LiveImBindingGate(); val old = gate.connect(setOf("room"), observer); val new = gate.connect(setOf("room"), observer)
        assertFalse(gate.accepts(old, "room")); assertNull(gate.takeTerminal(old)); assertTrue(gate.accepts(new, "room"))
        assertSame(observer, gate.takeTerminal(new)); assertNull(gate.takeTerminal(new)); assertFalse(gate.accepts(new, "room"))
    }
}
