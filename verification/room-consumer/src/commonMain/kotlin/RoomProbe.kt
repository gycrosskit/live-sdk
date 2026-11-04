import com.dgtang.live.room.*
import kotlinx.serialization.json.Json

fun roomConfigurationProbe(raw: String) = parseLiveRoomConfiguration(Json.parseToJsonElement(raw))
fun roomStateProbe(roomId: String, definitions: List<LivePluginDefinition>) = LiveRoomConfigurationState(roomId, definitions)
fun <T : LiveOverlayItem> enqueueProbe(state: LiveOverlayState<T>, item: T, now: Long) = LiveOverlayReducer.enqueue(state, item, now)
