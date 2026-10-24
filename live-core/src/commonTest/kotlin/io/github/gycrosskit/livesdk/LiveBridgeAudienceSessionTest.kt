package io.github.gycrosskit.livesdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LiveBridgeAudienceSessionTest {
    @Test
    fun `bridge reuses snapshot deduplication and waits for old leave before joining next room`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gate = Gate()
        val a = Platform()
        val b = Platform()
        val first = LiveBridgeAudienceSession("A", a, LivePlaybackCallbacks(), gate, CoroutineScope(coroutineContext + SupervisorJob()))
        val second = LiveBridgeAudienceSession("B", b, LivePlaybackCallbacks(), gate, CoroutineScope(coroutineContext + SupervisorJob()))
        try {
            runCurrent()
            assertEquals(1, a.joins)
            assertEquals(0, b.joins)
            a.observer.joined(host("A"), "notice")
            val message = LiveAudienceMessageSnapshot(1, 2.0, "viewer", "name", "", "hello")
            a.observer.messages(listOf(message, message))
            a.observer.audience(listOf(LiveAudienceUserSnapshot("viewer", "", "")), 10)
            assertEquals(listOf(message), first.snapshot.messages)
            assertEquals(10, first.snapshot.audienceCount)
            first.release()
            runCurrent()
            assertEquals(0, b.joins)
            a.observer.messages(listOf(message.copy(content = "late")))
            assertEquals(1, first.snapshot.messages.size)
            a.leaveFinished(true, 0, "")
            runCurrent()
            assertEquals(1, b.joins)
            b.observer.joined(host("B"), "")
            assertTrue(second.snapshot.interactionReady)
            assertFalse(second.enterPictureInPicture(true))
            second.updatePictureInPicture(true)
            assertFalse(second.snapshot.pictureInPicture)
        } finally {
            first.release(); second.release()
            a.leaveFinished(true, 0, ""); b.leaveFinished(true, 0, "")
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `release during join rejects late success and barrage acknowledgements`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val platform = Platform()
        var successes = 0
        val session = LiveBridgeAudienceSession("A", platform, LivePlaybackCallbacks(onJoinSucceeded = { successes++ }), Gate(), CoroutineScope(coroutineContext + SupervisorJob()))
        try {
            runCurrent()
            platform.observer.joined(host("A"), "")
            var sent: Boolean? = null
            session.sendBarrage("hello") { success, _ -> sent = success }
            session.like()
            session.release()
            platform.barrageFinished(true, "")
            assertEquals(false, sent)
            platform.observer.joined(host("A"), "")
            assertEquals(1, successes)
            assertFalse(session.snapshot.interactionReady)
            assertEquals(listOf(1), platform.likes)
        } finally {
            session.release(); platform.leaveFinished(true, 0, "")
            runCurrent(); Dispatchers.resetMain()
        }
    }

    @Test
    fun `released in-flight join still settles through leave without publishing success`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val platform = Platform()
        var successes = 0
        val session = LiveBridgeAudienceSession("A", platform, LivePlaybackCallbacks(onJoinSucceeded = { successes++ }), Gate(), CoroutineScope(coroutineContext + SupervisorJob()))
        try {
            runCurrent(); session.release()
            assertEquals(0, platform.leaves)
            platform.observer.joined(host("A"), "")
            assertEquals(1, platform.leaves)
            assertEquals(0, successes)
            assertFalse(session.snapshot.interactionReady)
        } finally {
            session.release(); platform.leaveFinished(true, 0, "")
            runCurrent(); Dispatchers.resetMain()
        }
    }

    @Test
    fun `member events reuse semantic snapshots and reject callbacks outside the joined session`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val platform = Platform()
        val session = LiveBridgeAudienceSession("A", platform, LivePlaybackCallbacks(), Gate(), CoroutineScope(coroutineContext + SupervisorJob()))
        val user = LiveAudienceUserSnapshot("member", "name", "avatar")
        try {
            runCurrent()
            platform.observer.memberChanged(user, true, 1.0)
            assertTrue(session.snapshot.messages.isEmpty())
            platform.observer.joined(host("A"), "")
            platform.observer.memberChanged(user, true, 2.0)
            platform.observer.memberChanged(user, false, 3.0)
            val messages = session.snapshot.messages
            assertEquals(listOf(LiveAudienceMessageKind.MEMBER_JOINED, LiveAudienceMessageKind.MEMBER_LEFT), messages.map { it.kind })
            assertTrue(messages.all { it.sequence < 0 && it.content.isEmpty() })
            assertEquals(listOf("name", "name"), messages.map { it.senderName })
            assertEquals(listOf(2.0, 3.0), messages.map { it.timestampSeconds })
            session.release()
            platform.observer.memberChanged(user, true, 4.0)
            assertEquals(messages, session.snapshot.messages)
        } finally {
            session.release(); platform.leaveFinished(true, 0, "")
            runCurrent(); Dispatchers.resetMain()
        }
    }

    @Test
    fun `only actual platform PiP callbacks change snapshots and callbacks are ignored after release`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val platform = Platform()
        val changes = mutableListOf<Boolean>()
        val session = LiveBridgeAudienceSession("A", platform,
            LivePlaybackCallbacks(onPictureInPictureChanged = { changes += it }), Gate(), CoroutineScope(coroutineContext + SupervisorJob()))
        try {
            runCurrent()
            platform.observer.pictureInPictureChanged(true)
            assertFalse(session.snapshot.pictureInPicture)
            platform.observer.joined(host("A"), "")
            session.updatePictureInPicture(true)
            assertFalse(session.snapshot.pictureInPicture)
            assertTrue(changes.isEmpty())
            platform.observer.pictureInPictureChanged(true)
            assertTrue(session.snapshot.pictureInPicture)
            platform.observer.pictureInPictureChanged(false)
            assertFalse(session.snapshot.pictureInPicture)
            assertEquals(listOf(true, false), changes)
            platform.observer.pictureInPictureChanged(true)
            session.release()
            assertFalse(session.snapshot.pictureInPicture)
            platform.observer.pictureInPictureChanged(true)
            assertFalse(session.snapshot.pictureInPicture)
            assertEquals(listOf(true, false, true), changes)
        } finally {
            session.release(); platform.leaveFinished(true, 0, "")
            runCurrent(); Dispatchers.resetMain()
        }
    }

    @Test
    fun `missing room uses shared ended semantics and the next room waits only for real cleanup`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gate = Gate()
        val ended = Platform()
        val running = Platform()
        var unavailable = 0
        var failures = 0
        val first = LiveBridgeAudienceSession("ended", ended, LivePlaybackCallbacks(
            onLiveUnavailable = { unavailable++ }, onJoinFailed = { _, _ -> failures++ },
        ), gate, CoroutineScope(coroutineContext + SupervisorJob()))
        val second = LiveBridgeAudienceSession("running", running, LivePlaybackCallbacks(), gate,
            CoroutineScope(coroutineContext + SupervisorJob()))
        try {
            runCurrent()
            ended.observer.joinFailed(100004, "room not exist")
            assertEquals(1, unavailable)
            assertEquals(0, failures)
            assertFalse(first.snapshot.loading)
            assertEquals(1, ended.leaves)
            assertEquals(0, running.joins)
            ended.leaveFinished(true, 0, "")
            runCurrent()
            assertEquals(1, running.joins)
            running.observer.joined(host("running"), "")
            assertTrue(second.snapshot.interactionReady)
        } finally {
            first.release(); second.release()
            ended.leaveFinished(true, 0, ""); running.leaveFinished(true, 0, "")
            runCurrent(); Dispatchers.resetMain()
        }
    }

    private class Gate : AtomicAudienceSessionGate {
        private var owner: Any? = null
        private val waiting = ArrayDeque<Pair<Any, () -> Unit>>()
        override fun acquire(token: Any, liveId: String, onTimeout: () -> Unit, start: () -> Unit) {
            if (owner == null) { owner = token; start() } else waiting.addLast(token to start)
        }
        override fun release(token: Any) {
            if (owner !== token) { waiting.removeAll { it.first === token }; return }
            owner = null
            waiting.removeFirstOrNull()?.let { (next, start) -> owner = next; start() }
        }
    }

    @Test
    fun `bridge reuses follow generations and clears late follow state on release`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val platform = Platform()
        val session = LiveBridgeAudienceSession("A", platform, LivePlaybackCallbacks(), Gate(), CoroutineScope(coroutineContext + SupervisorJob()))
        try {
            runCurrent()
            platform.observer.joined(host("A").copy(owner = LiveAudienceUserSnapshot("host", "", "")), "")
            assertTrue(session.snapshot.host.followVisible)
            assertFalse(session.snapshot.host.followed)
            assertEquals(9L, session.snapshot.host.fansCount)
            session.toggleFollow(); session.toggleFollow()
            assertEquals(listOf(true), platform.followTargets)
            assertTrue(session.snapshot.host.followRequestRunning)
            platform.followFinished(LiveFollowResult.Success(Unit))
            assertTrue(session.snapshot.host.followed)
            session.toggleFollow()
            assertEquals(listOf(true, false), platform.followTargets)
            session.release()
            platform.followFinished(LiveFollowResult.Success(Unit))
            assertFalse(session.snapshot.host.followVisible)
            assertFalse(session.snapshot.host.followed)
            assertFalse(session.snapshot.host.followRequestRunning)
        } finally {
            session.release(); platform.leaveFinished(true, 0, "")
            runCurrent(); Dispatchers.resetMain()
        }
    }

    @Test
    fun `successful like batches including release tail retain the submitted identity`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val platform = Platform().apply { completeLikes = false }
        val reports = mutableListOf<Triple<String, String, Int>>()
        val session = LiveBridgeAudienceSession("A", platform, LivePlaybackCallbacks(), Gate(),
            CoroutineScope(coroutineContext + SupervisorJob()), LiveLikeReporter { liveId, userId, count -> reports += Triple(liveId, userId, count) })
        try {
            runCurrent(); platform.observer.joined(host("A"), "")
            session.like(); session.like()
            session.release()
            assertEquals(listOf(1, 1), platform.likes)
            platform.currentUserId = "next-account"
            platform.likeReplies[0](true); platform.likeReplies[1](true)
            assertEquals(listOf(Triple("A", "viewer", 1), Triple("A", "viewer", 1)), reports)
            session.like(); assertEquals(2, platform.likes.size)
        } finally {
            session.release(); platform.leaveFinished(true, 0, "")
            runCurrent(); Dispatchers.resetMain()
        }
    }

    @Test
    fun `failed like batches do not report success and released retries cannot revive the session`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val platform = Platform().apply { completeLikes = false }
        var reports = 0
        val session = LiveBridgeAudienceSession("A", platform, LivePlaybackCallbacks(), Gate(),
            CoroutineScope(coroutineContext + SupervisorJob()), LiveLikeReporter { _, _, _ -> reports++ })
        try {
            runCurrent(); platform.observer.joined(host("A"), "")
            session.like(); session.release(); platform.likeReplies.single()(false)
            runCurrent()
            assertEquals(0, reports); assertEquals(listOf(1), platform.likes)
        } finally {
            session.release(); platform.leaveFinished(true, 0, "")
            runCurrent(); Dispatchers.resetMain()
        }
    }

    private class Platform : LiveAudiencePlatform {
        override var currentUserId = "viewer"
        val followTargets = mutableListOf<Boolean>()
        var followFinished: (LiveFollowResult<Unit>) -> Unit = {}
        override fun checkFollowed(userId: String, callback: (LiveFollowResult<Boolean>) -> Unit) { callback(LiveFollowResult.Success(false)) }
        override fun updateFollow(userId: String, followed: Boolean, callback: (LiveFollowResult<Unit>) -> Unit) {
            followTargets += followed; followFinished = callback
        }
        override fun fetchFans(userId: String, callback: (LiveFollowResult<Long>) -> Unit) { callback(LiveFollowResult.Success(9L)) }
        var completeLikes = true
        val likeReplies = mutableListOf<(Boolean) -> Unit>()
        lateinit var observer: LiveAudiencePlatformObserver
        var joins = 0
        var leaves = 0
        var leaveFinished: (Boolean, Int, String) -> Unit = { _, _, _ -> }
        var barrageFinished: (Boolean, String) -> Unit = { _, _ -> }
        val likes = mutableListOf<Int>()
        override fun join(liveId: String, observer: LiveAudiencePlatformObserver) { joins++; this.observer = observer }
        override fun leave(onFinished: (Boolean, Int, String) -> Unit) { leaves++; leaveFinished = onFinished }
        override fun sendBarrage(message: String, onFinished: (Boolean, String) -> Unit) { barrageFinished = onFinished }
        override fun sendLike(count: Int, onFinished: (Boolean) -> Unit) { likes += count; if (completeLikes) onFinished(true) else likeReplies += onFinished }
        override fun refreshAudience() = Unit
    }

    private fun host(id: String) = LiveAudienceContentSnapshot.Empty.host.copy(roomId = id)
}
