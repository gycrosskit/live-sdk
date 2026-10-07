package io.github.gycrosskit.livesdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosKuiklyLiveLifecycleTest {
    @Test fun sessionReleaseAcceptsNewRoomButNodeReleaseIsTerminal() {
        // Missing native bridge makes an attempted session observable without mocking a vendor SDK.
        assertNull(IosLiveSdkRuntime.bridge)
        val events = mutableListOf<String>()
        IosLiveSdkRuntime.updateSessionReady(true)
        val controller = IosKuiklyLiveController(events::add)
        try {
            controller.setVisible(true)
            controller.configure("""{"liveId":"first-room","preview":true}""")
            assertEquals(1, events.size)
            controller.command("release", "{}") {}
            controller.setVisible(false)
            controller.setVisible(true)
            assertEquals(1, events.size)
            controller.configure("""{"liveId":"next-room","preview":true}""")
            assertEquals(2, events.size)
            controller.release()
            controller.configure("""{"liveId":"destroyed-room","preview":true}""")
            assertEquals(2, events.size)
        } finally {
            controller.release()
            IosLiveSdkRuntime.updateSessionReady(false)
        }
    }
}
