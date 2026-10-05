package io.github.gycrosskit.livesdk

import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class KuiklyLivePictureInPictureTest {
    @Test fun unreadyOrReleasedViewNeverClaimsPictureInPictureAcceptance() {
        val view = GycLiveView(RuntimeEnvironment.getApplication())
        // 在 View 尚未挂载/没有观看实例时也必须完成回执，不能悬挂宿主等待。
        for (params in listOf("{\"wideContent\":true}", "{\"wideContent\":false}", "{\"wideContent\":\"true\"}", "{}", "invalid")) {
            var reply: Any? = null
            view.call("enterPictureInPicture", params) { reply = it }
            assertEquals(mapOf("accepted" to false), reply)
        }
        view.call("updatePictureInPicture", "{\"enabled\":true}", null)
        view.call("release", null, null)
        var reply: Any? = null
        view.call("enterPictureInPicture", "{\"wideContent\":true}") { reply = it }
        assertEquals(mapOf("accepted" to false), reply)
    }
}
