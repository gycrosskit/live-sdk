package io.github.gycrosskit.livesdk

import android.os.Looper
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AtomicXSessionValidationTest {
    @Test
    fun invalidLoginFailsOnCallingThreadWithoutPostingMainWork() {
        val context = RuntimeEnvironment.getApplication()
        val callbacks = AtomicInteger()
        val callback = object : AtomicXSession.Callback {
            override fun onSuccess() { callbacks.incrementAndGet() }
            override fun onFailure(code: Int, message: String) { callbacks.incrementAndGet() }
        }
        val invalidCredentials = listOf(
            Triple(0, "member", "signature"),
            Triple(100, " \t\n", "signature"),
            Triple(100, "member", " \t\n"),
        )
        val validation = FutureTask {
            for ((sdkAppId, userId, userSig) in invalidCredentials) {
                assertFailsWith<IllegalArgumentException> {
                    AtomicXSession.login(context, sdkAppId, userId, userSig, "member", "", callback)
                }
            }
        }
        Thread(validation, "invalid-live-login").apply { isDaemon = true; start() }
        validation.get(5, TimeUnit.SECONDS)
        // 调用点必须已同步收到异常，Main 队列不能留下稍后崩溃或调用 SDK 的任务。
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, callbacks.get())
    }
}
