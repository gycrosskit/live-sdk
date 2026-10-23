package io.github.gycrosskit.livesdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KuiklyLiveEventInitializationTest {
    @Test fun eventFactoryWaitsForKuiklyToBindThePager() {
        val view = KuiklyLiveView().apply { pagerId = "live-event-initialization" }

        // 真实 Kuikly 先调用 createEvent，再把 View 的 pagerId 写入事件对象。
        val event = view.getViewEvent()

        assertEquals(view.pagerId, event.pagerId)
        assertTrue(event.isEmpty())
    }
}
