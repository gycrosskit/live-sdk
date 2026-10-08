package verification
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.ui.Modifier
import io.github.gycrosskit.livesdk.kuikly.LiveVideo
@Composable fun LiveApi(room: String, active: Boolean) {
 LiveVideo(room, preview = true, active = active, modifier = Modifier, onEvent = {}, onController = { it?.release() })
}
