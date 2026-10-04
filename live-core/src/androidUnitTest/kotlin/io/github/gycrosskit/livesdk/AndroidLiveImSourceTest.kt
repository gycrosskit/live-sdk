package io.github.gycrosskit.livesdk
import com.tencent.imsdk.v2.*
import kotlin.test.*
class AndroidLiveImSourceTest {
    private fun member(id: String): V2TIMGroupMemberInfo = object : V2TIMGroupMemberInfo() { override fun getUserID() = id }
    private class Sdk : LiveImRegistration {
        val groups = mutableListOf<V2TIMGroupListener>(); val accounts = mutableListOf<V2TIMSDKListener>()
        var added = 0; var removed = 0; var failAccount = false; var failRemoveGroup = false
        override fun currentUserId() = "self"
        override fun add(listener: V2TIMGroupListener) { groups += listener; added++ }
        override fun add(listener: V2TIMSDKListener) { if (failAccount) error("mock register failure"); accounts += listener; added++ }
        override fun remove(listener: V2TIMGroupListener) { if (failRemoveGroup) error("mock remove failure"); groups -= listener; removed++ }
        override fun remove(listener: V2TIMSDKListener) { accounts -= listener; removed++ }
    }
    private class Observer : LiveImObserver {
        val events = mutableListOf<String>()
        override fun onRestCustomData(groupId: String, payload: String) { events += payload }
        override fun onCurrentUserRemoved(groupId: String, operatorUserId: String) { events += "removed:$operatorUserId" }
        override fun onGroupDismissed(groupId: String) { events += "dismissed" }
        override fun onKickedOffline() { events += "offline" }
        override fun onUserSigExpired() { events += "expired" }
    }
    @Test fun oldGenerationAndForeignGroupCannotDeliverAndTerminalIsIdempotent() {
        val sdk = Sdk(); val source = AndroidLiveImSource(sdk); val first = Observer(); val second = Observer()
        source.connect(setOf(" room "), first)
        val oldGroup = sdk.groups.single(); val oldAccount = sdk.accounts.single()
        oldGroup.onReceiveRESTCustomData("room", "first".encodeToByteArray())
        source.connect(setOf("room"), second)
        assertEquals(2, sdk.removed)
        oldGroup.onReceiveRESTCustomData("room", "late".encodeToByteArray()); oldAccount.onKickedOffline()
        val group = sdk.groups.single(); val account = sdk.accounts.single()
        group.onReceiveRESTCustomData("foreign", "foreign".encodeToByteArray())
        group.onReceiveRESTCustomData("room", "second".encodeToByteArray())
        val op = member("operator")
        group.onMemberKicked("room", op, mutableListOf(member("other")))
        group.onMemberKicked("room", op, mutableListOf(member("self")))
        account.onUserSigExpired(); account.onUserSigExpired(); account.onKickedOffline()
        assertEquals(listOf("first"), first.events)
        assertEquals(listOf("second", "removed:operator", "expired"), second.events)
        assertTrue(sdk.groups.isEmpty() && sdk.accounts.isEmpty())
        source.close(); source.close(); assertFailsWith<IllegalStateException> { source.connect(setOf("room"), first) }
    }
    @Test fun partialRegistrationFailureReleasesBothListenersAndSourceCanRetry() {
        val sdk = Sdk(); sdk.failAccount = true; val source = AndroidLiveImSource(sdk)
        assertFails { source.connect(setOf("room"), Observer()) }
        assertTrue(sdk.groups.isEmpty()); assertEquals(2, sdk.removed)
        sdk.failAccount = false; source.connect(setOf("room"), Observer()); source.disconnect(); source.close()
        assertTrue(sdk.groups.isEmpty() && sdk.accounts.isEmpty())
    }
    @Test fun failedRemovalKeepsRetryHandleWhileOldCallbacksStayInvalid() {
        val sdk = Sdk(); val source = AndroidLiveImSource(sdk); val observer = Observer()
        source.connect(setOf("room"), observer)
        val group = sdk.groups.single(); val account = sdk.accounts.single()
        sdk.failRemoveGroup = true
        assertFails { account.onKickedOffline() }
        account.onKickedOffline(); group.onReceiveRESTCustomData("room", "late".encodeToByteArray())
        assertEquals(listOf("offline"), observer.events)
        assertTrue(sdk.accounts.isEmpty()); assertEquals(1, sdk.groups.size)
        assertFails { source.close() }
        sdk.failRemoveGroup = false; source.close()
        assertTrue(sdk.groups.isEmpty())
    }
}
