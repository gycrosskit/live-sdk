package io.github.gycrosskit.livesdk

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [ShadowLiveAudienceSession::class, ShadowLivePreviewSession::class])
@LooperMode(LooperMode.Mode.PAUSED)
class KuiklyLiveLifecycleTest {
    @Test fun previewStartsOnlyTheLatestRoomAfterItsVideoChildrenAttach() = withHost { parent, view, lifecycle ->
        view.setProp("room", previewRoom("old"))
        view.setProp("room", previewRoom("current"))
        parent.addView(view)
        assertTrue(ShadowLivePreviewSession.created.isEmpty())
        idleMain()

        val player = ShadowLivePreviewSession.created.single()
        assertEquals("current", player.liveId)
        assertEquals(1, player.starts)
        assertEquals(1, lifecycle.observerCount)
        view.setProp("room", previewRoom("superseded"))
        view.setProp("room", previewRoom("latest"))
        assertEquals(1, ShadowLivePreviewSession.created.size)
        idleMain()
        assertEquals(listOf("current", "latest"), ShadowLivePreviewSession.created.map { it.liveId })
    }

    @Test fun readinessAndSynchronousLifecycleCallbacksCannotBypassTheAttachGate() = withHost { parent, view, lifecycle ->
        lifecycle.currentState = Lifecycle.State.CREATED
        AndroidLiveSdkRuntime.updateSessionReady(false)
        view.setProp("room", previewRoom("room"))
        parent.addView(view)
        AndroidLiveSdkRuntime.updateSessionReady(true)
        lifecycle.currentState = Lifecycle.State.STARTED
        lifecycle.currentState = Lifecycle.State.RESUMED
        assertTrue(ShadowLivePreviewSession.created.isEmpty())
        idleMain()

        val player = ShadowLivePreviewSession.created.single()
        assertEquals(1, player.starts)
        lifecycle.currentState = Lifecycle.State.STARTED
        lifecycle.currentState = Lifecycle.State.RESUMED
        idleMain()
        assertEquals(1, ShadowLivePreviewSession.created.size)
        assertEquals(1, player.starts)
        assertEquals(1, lifecycle.observerCount)
    }

    @Test fun detachCancelsPendingStartAndReattachUsesTheLatestRoomOnce() = withHost { parent, view, lifecycle ->
        view.setProp("room", previewRoom("first"))
        parent.addView(view)
        idleMain()
        val first = ShadowLivePreviewSession.created.single()
        view.setProp("room", previewRoom("latest"))
        parent.removeView(view)
        AndroidLiveSdkRuntime.updateSessionReady(false)
        AndroidLiveSdkRuntime.updateSessionReady(true)
        idleMain()
        assertTrue(first.released)
        assertEquals(1, ShadowLivePreviewSession.created.size)
        assertEquals(0, lifecycle.observerCount)

        parent.addView(view)
        assertEquals(1, ShadowLivePreviewSession.created.size)
        idleMain()
        assertEquals(listOf("first", "latest"), ShadowLivePreviewSession.created.map { it.liveId })
        assertEquals(1, ShadowLivePreviewSession.created.last().starts)
        assertEquals(1, lifecycle.observerCount)
    }

    @Test fun explicitReleaseCancelsPendingStartUntilANewRoomIsSubmitted() = withHost { parent, view, lifecycle ->
        view.setProp("room", previewRoom("cancelled"))
        parent.addView(view)
        view.call("release", null, null)
        idleMain()
        AndroidLiveSdkRuntime.updateSessionReady(false)
        AndroidLiveSdkRuntime.updateSessionReady(true)
        lifecycle.currentState = Lifecycle.State.RESUMED
        parent.removeView(view)
        parent.addView(view)
        idleMain()
        assertTrue(ShadowLivePreviewSession.created.isEmpty())

        view.setProp("room", previewRoom("new-room"))
        idleMain()
        assertEquals("new-room", ShadowLivePreviewSession.created.single().liveId)
    }

    @Test fun destroyCancelsPendingStartAndIsTerminalAcrossReattach() = withHost { parent, view, lifecycle ->
        view.setProp("room", previewRoom("pending"))
        parent.addView(view)
        view.onDestroy()
        view.onDestroy()
        view.setProp("room", previewRoom("late"))
        AndroidLiveSdkRuntime.updateSessionReady(false)
        AndroidLiveSdkRuntime.updateSessionReady(true)
        lifecycle.currentState = Lifecycle.State.RESUMED
        parent.removeView(view)
        parent.addView(view)
        idleMain()
        assertTrue(ShadowLivePreviewSession.created.isEmpty())
        assertTrue(ShadowLiveAudienceSession.created.isEmpty())
        assertEquals(0, lifecycle.observerCount)
    }

    @Test fun destroyReleasesTheAudienceAndPreservesItsInjectedReporter() = withHost { parent, view, lifecycle ->
        val reporter = LiveLikeReporter { _, _, _ -> }
        view.likeReporter = reporter
        view.setProp("room", "{\"liveId\":\"audience\"}")
        parent.addView(view)
        assertTrue(ShadowLiveAudienceSession.created.isEmpty())
        idleMain()
        val player = ShadowLiveAudienceSession.created.single()
        assertSame(reporter, player.reporter)
        view.onDestroy()
        assertTrue(player.released)
        assertEquals(0, lifecycle.observerCount)
        view.setProp("room", "{\"liveId\":\"late\"}")
        idleMain()
        assertEquals(1, ShadowLiveAudienceSession.created.size)
    }

    @Test fun startedHostReceivesInitialAndUpdatedSnapshotsAcrossReattach() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val parent = FrameLayout(activity.get())
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.STARTED }
        }
        parent.setViewTreeLifecycleOwner(owner)
        activity.get().setContentView(parent)
        val events = mutableListOf<Map<*, *>>()
        val callback: KuiklyRenderCallback = { events.add(it as Map<*, *>) }
        val view = GycLiveView(activity.get())
        view.setProp("liveEvent", callback)
        view.setProp("room", "{\"liveId\":\"test-room\"}")
        AndroidLiveSdkRuntime.updateSessionReady(true)
        try {
            // 真正 attach 到已 STARTED 的 owner；同步补发事件仍须等完整子树挂载。
            parent.addView(view)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(events.any { it["type"] == "joinSucceeded" })
            fun snapshots() = events.filter { it["type"] == "snapshot" }.map { it["snapshot"] as Map<*, *> }
            val initial = snapshots().single()
            assertEquals(false, initial["loading"])
            assertEquals(true, initial["interactionReady"])
            assertEquals(12, initial["audienceCount"])
            assertEquals("Test host", ((initial["host"] as Map<*, *>)["owner"] as Map<*, *>)["name"])

            val first = ShadowLiveAudienceSession.latest
            first.state.value = first.state.value.copy(audienceCount = 21)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(listOf(12, 21), snapshots().map { it["audienceCount"] })
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(2, snapshots().size)

            parent.removeView(view)
            assertTrue(first.released)
            first.state.value = first.state.value.copy(audienceCount = 99)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(2, snapshots().size)
            parent.addView(view)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(listOf(12, 21, 12), snapshots().map { it["audienceCount"] })
        } finally {
            parent.removeView(view)
            AndroidLiveSdkRuntime.updateSessionReady(false)
            activity.pause().stop().destroy()
        }
    }

    private fun withHost(block: (FrameLayout, GycLiveView, LifecycleRegistry) -> Unit) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val parent = FrameLayout(activity.get())
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.STARTED }
        }
        parent.setViewTreeLifecycleOwner(owner)
        activity.get().setContentView(parent)
        val view = GycLiveView(activity.get())
        ShadowLivePreviewSession.created.clear()
        ShadowLiveAudienceSession.created.clear()
        AndroidLiveSdkRuntime.updateSessionReady(true)
        try {
            block(parent, view, owner.lifecycle)
        } finally {
            view.onDestroy()
            parent.removeView(view)
            AndroidLiveSdkRuntime.updateSessionReady(false)
            activity.pause().stop().destroy()
            idleMain()
        }
    }

    private fun previewRoom(liveId: String) = "{\"liveId\":\"$liveId\",\"preview\":true}"
    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()
}

/** 仅替换厂商会话边界；真实 View attach、Lifecycle 同步回调与 StateFlow 订阅均不替换。 */
@Implements(AndroidLiveAudienceSession::class)
class ShadowLiveAudienceSession {
    private lateinit var view: View
    lateinit var reporter: LiveLikeReporter
        private set
    val state = MutableStateFlow(LiveAudienceContentSnapshot.Empty.copy(
        loading = false,
        interactionReady = true,
        audienceCount = 12,
        host = LiveAudienceContentSnapshot.Empty.host.copy(owner = LiveAudienceUserSnapshot("host", "Test host", "")),
    ))
    var released = false
        private set

    @Implementation fun __constructor__(context: Context, liveId: String, callbacks: LivePlaybackCallbacks, reporter: LiveLikeReporter) {
        view = FrameLayout(context)
        this.reporter = reporter
        latest = this
        created += this
        callbacks.onJoinSucceeded()
    }
    @Implementation fun getView(): View = view
    @Implementation fun getSnapshots(): StateFlow<LiveAudienceContentSnapshot> = state
    @Implementation fun release() { released = true }

    companion object {
        lateinit var latest: ShadowLiveAudienceSession
        val created = mutableListOf<ShadowLiveAudienceSession>()
    }
}

/** 只替换厂商预览会话；真实子 View 必须已挂载，启动/停止由真实 GycLiveView 驱动。 */
@Implements(AndroidLivePreviewSession::class)
class ShadowLivePreviewSession {
    private lateinit var view: View
    private lateinit var target: View
    private lateinit var onStateChanged: (LivePreviewState) -> Unit
    lateinit var liveId: String
        private set
    var starts = 0
        private set
    var released = false
        private set
    private var playing = false

    @Implementation fun __constructor__(context: Context, liveId: String, onStateChanged: (LivePreviewState) -> Unit) {
        this.liveId = liveId
        this.onStateChanged = onStateChanged
        target = View(context)
        view = FrameLayout(context).apply { addView(target) }
        created += this
    }

    @Implementation fun getView(): View = view

    @Implementation fun update(active: Boolean, visible: Boolean) {
        assertFalse(released)
        if (active && visible) {
            check(target.isAttachedToWindow) { "Preview started before its video target attached" }
            if (!playing) {
                playing = true
                starts++
                onStateChanged(LivePreviewState.PLAYING)
            }
        } else playing = false
    }

    @Implementation fun release() { released = true; playing = false }

    companion object {
        val created = mutableListOf<ShadowLivePreviewSession>()
    }
}
