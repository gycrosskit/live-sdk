import com.dgtang.live.room.*
import kotlin.test.*

class RoomConsumerTest {
    private data class Item(override val key: String, override val expiresAtElapsedRealtimeMillis: Long?) : LiveOverlayItem {
        override val priority = LiveOverlayPriority.USER
        override val resumable = true
    }

    @Test fun publishedQueueAndRoomOwnerKeepTheirBoundaries() {
        assertNull(enqueueProbe(LiveOverlayState(), Item("expired", 10), 10).active)
        val owner = roomStateProbe("room", emptyList())
        assertFalse(owner.updateRoom(LiveRoomConfigurationSnapshot("old-room", 100, LiveRoomConfiguration())))
        assertTrue(owner.updateRoom(LiveRoomConfigurationSnapshot("room", 2, LiveRoomConfiguration())))
        assertFalse(owner.updateRoom(LiveRoomConfigurationSnapshot("room", 1, LiveRoomConfiguration())))
        assertEquals(emptyList(), roomConfigurationProbe("{\"plugins\":[]}")?.plugins)
    }
}
