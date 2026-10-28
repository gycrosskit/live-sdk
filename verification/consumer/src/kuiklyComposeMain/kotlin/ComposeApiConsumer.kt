package verification
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.ui.Modifier
import io.github.gycrosskit.livesdk.kuikly.LiveVideo
import io.github.gycrosskit.livesdk.LiveLikeReporter
import io.github.gycrosskit.livesdk.LivePlaybackCallbacks
import io.github.gycrosskit.livesdk.dispatchLivePlaybackEvent
import io.github.gycrosskit.livesdk.toLiveAudienceSnapshot
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
@Composable fun LiveApi(room: String, active: Boolean) {
 LiveVideo(room, preview = true, active = active, modifier = Modifier, onEvent = {}, onController = { it?.release() })
}

@Composable
fun InjectedLiveVideo(room: String, reporter: LiveLikeReporter) {
    LiveVideo(room, likeReporter = reporter, roomExtras = mapOf("hostRoom" to "opaque-host-id"))
}

fun ConsumeLiveEvent(event: JSONObject) {
    event.dispatchLivePlaybackEvent(LivePlaybackCallbacks())
    event.toLiveAudienceSnapshot()?.messages?.forEach { it.kind }
}
