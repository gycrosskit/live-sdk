package io.github.gycrosskit.livesdk

import android.app.Activity
import android.content.Context
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
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [ShadowLiveAudienceSession::class])
class KuiklyLiveLifecycleTest {
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
            // 真正 attach 到已 STARTED 的 owner；addObserver 会同步补发事件并创建观看实例。
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
}

/** 仅替换厂商会话边界；真实 View attach、Lifecycle 同步回调与 StateFlow 订阅均不替换。 */
@Implements(AndroidLiveAudienceSession::class)
class ShadowLiveAudienceSession {
    private lateinit var view: View
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
        latest = this
        callbacks.onJoinSucceeded()
    }
    @Implementation fun getView(): View = view
    @Implementation fun getSnapshots(): StateFlow<LiveAudienceContentSnapshot> = state
    @Implementation fun release() { released = true }

    companion object {
        lateinit var latest: ShadowLiveAudienceSession
    }
}
