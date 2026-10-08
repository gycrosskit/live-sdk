@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package io.github.gycrosskit.livesdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import platform.UIKit.UIView
import kotlin.test.*

class IosAudienceCommandOwnershipTest {
    @Test fun queuedAndReleasedCommandsNeverReachAnotherRoom() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val bridge = RecordingAudienceBridge()
        val first = IosAtomicAudienceView(bridge, "A", LivePlaybackCallbackListener { LivePlaybackCallbacks() }, LiveLikeReporter.None)
        first.mount(UIView())
        bridge.observers.getValue("A").onJoinSucceeded()
        val second = IosAtomicAudienceView(bridge, "B", LivePlaybackCallbackListener { LivePlaybackCallbacks() }, LiveLikeReporter.None)
        second.mount(UIView())
        try {
            assertEquals(listOf("A"), bridge.created)
            var queuedResult: Boolean? = null
            second.sendBarrage("queued B") { success, _ -> queuedResult = success }
            second.like()
            second.refreshAudience()
            assertEquals(false, queuedResult)
            assertTrue(bridge.writes.isEmpty())
            assertTrue(bridge.refreshes.isEmpty())

            var lateResult: Boolean? = null
            first.sendBarrage("owned A") { success, _ -> lateResult = success }
            first.like()
            first.like() // 待发尾批次只能在 A 仍持有令牌时 flush。
            first.release()
            assertEquals(listOf("barrage:A:owned A", "like:A:1", "like:A:1"), bridge.writes)
            first.sendBarrage("released A") { success, _ -> assertFalse(success) }
            first.like()
            first.refreshAudience()
            assertEquals(listOf("A"), bridge.created)
            bridge.completeLeave()
            assertEquals(listOf("A", "B"), bridge.created)
            bridge.observers.getValue("B").onJoinSucceeded()
            bridge.pendingBarrage!!.onSuccess()
            assertEquals(false, lateResult)
            advanceTimeBy(6_001)
            runCurrent()
            assertEquals(3, bridge.writes.size)
            second.sendBarrage("owned B") { _, _ -> }
            second.like()
            assertEquals(listOf("barrage:B:owned B", "like:B:1"), bridge.writes.takeLast(2))
            second.release()
            second.sendBarrage("released B") { success, _ -> assertFalse(success) }
            second.like()
            assertEquals(5, bridge.writes.size)
        } finally {
            first.release(); second.release(); bridge.completeLeave()
            Dispatchers.resetMain()
        }
    }
}

private class RecordingAudienceBridge : IosLiveSdkBridge {
    val created = mutableListOf<String>()
    val observers = mutableMapOf<String, IosAudiencePlayerObserver>()
    val writes = mutableListOf<String>()
    val refreshes = mutableListOf<String>()
    private var current = ""
    private var leaving: IosLiveOperationCallback? = null
    var pendingBarrage: IosLiveOperationCallback? = null
    fun completeLeave() { val callback = leaving; leaving = null; current = ""; callback?.onSuccess() }
    override fun makeAudienceView(liveId: String, observer: IosAudiencePlayerObserver): UIView {
        current = liveId; created += liveId; observers[liveId] = observer; return UIView()
    }
    override fun releaseAudienceView(view: UIView, callback: IosLiveOperationCallback) { leaving = callback }
    override fun sendBarrage(message: String, callback: IosLiveOperationCallback) { writes += "barrage:$current:$message"; pendingBarrage = callback }
    override fun sendLike(count: Int, callback: IosLiveOperationCallback) { writes += "like:$current:$count"; callback.onSuccess() }
    override fun refreshAudience() { refreshes += current }
    override fun currentUserId() = "viewer"
    override fun isLoggedInAs(userId: String) = true
    override fun updateProfile(userId: String, nickname: String, avatarUrl: String) = Unit
    override fun login(sdkAppId: Int, userId: String, userSig: String, nickname: String, avatarUrl: String, callback: IosLiveOperationCallback) = Unit
    override fun logout(callback: IosLiveOperationCallback) = Unit
    override fun makePreviewView(liveId: String, observer: IosLivePreviewObserver) = UIView()
    override fun releasePreviewView(view: UIView) = Unit
    override fun stopPreview() = Unit
    override fun connectLiveIm(groupIds: List<String>, observer: IosLiveImObserver) = Unit
    override fun disconnectLiveIm() = Unit
    override fun checkFollowed(userId: String, callback: IosLiveBooleanCallback) = Unit
    override fun updateFollow(userId: String, followed: Boolean, callback: IosLiveOperationCallback) = Unit
    override fun fetchFans(userId: String, callback: IosLiveLongCallback) = Unit
    override fun enterPictureInPicture(wideContent: Boolean) = false
}
