package io.github.gycrosskit.livesdk

import kotlin.test.Test
import kotlin.test.assertEquals

class LivePlaybackCallbackListenerTest {
    @Test
    fun `listener resolves latest callback instance for every event`() {
        val events = mutableListOf<String>()
        var callbacks = LivePlaybackCallbacks(onJoinStarted = { events += "old" })
        val listener = LivePlaybackCallbackListener { callbacks }

        listener.onJoinStarted()
        callbacks = LivePlaybackCallbacks(
            onJoinSucceeded = { events += "joined" },
            onJoinFailed = { code, message -> events += "failed:$code:$message" },
            onLiveUnavailable = { events += "unavailable:$it" },
            onKickedOut = { events += "kicked" },
            onLiveEnded = { events += "ended" },
            onCurrentUserMessageDisabled = { events += "muted:$it" },
            onPictureInPictureChanged = { events += "pip:$it" },
        )
        listener.onJoinSucceeded()
        listener.onJoinFailed(7, "network")
        listener.onLiveUnavailable("closed")
        listener.onKickedOut()
        listener.onLiveEnded()
        listener.onCurrentUserMessageDisabled(true)
        listener.onPictureInPictureChanged(true)

        assertEquals(
            listOf(
                "old",
                "joined",
                "failed:7:network",
                "unavailable:closed",
                "kicked",
                "ended",
                "muted:true",
                "pip:true",
            ),
            events,
        )
    }
}
