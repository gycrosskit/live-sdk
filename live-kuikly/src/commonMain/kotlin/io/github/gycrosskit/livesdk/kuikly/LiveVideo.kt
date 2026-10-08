package io.github.gycrosskit.livesdk.kuikly

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.tencent.kuikly.compose.extension.MakeKuiklyComposeNode
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import io.github.gycrosskit.livesdk.KuiklyLiveView

/**
 * Android/iOS KuiklyCompose 视频入口，复用现有观看/预览会话；宿主先注册 GycLiveView 并准备账号。
 * preview=true 为静音预览；active=false 停止当前房间，恢复 true 允许重新开始。
 * onEvent 交付原生事件和 snapshot JSON；房间 UI、本地化与业务点赞上报由宿主提供。
 * onController 提供当前节点的互动命令，离开 Composition 回报 null，不能跨页面保留。
 */
@Composable
fun LiveVideo(
    liveId: String,
    preview: Boolean = false,
    active: Boolean = true,
    modifier: Modifier = Modifier,
    onEvent: (JSONObject) -> Unit = {},
    onController: (KuiklyLiveView?) -> Unit = {},
) {
    val currentEvent by rememberUpdatedState(onEvent)
    val view = remember { KuiklyLiveView() }
    val room = Triple(liveId, preview, active)
    val acceptingEvents = remember(view) { booleanArrayOf(true) }
    val submitted = remember(view) { arrayOf<Triple<String, Boolean, Boolean>?>(null) }
    DisposableEffect(view, onController) {
        onController(view)
        onDispose { onController(null) }
    }
    DisposableEffect(view) {
        acceptingEvents[0] = true
        onDispose {
            acceptingEvents[0] = false
            view.release()
        }
    }
    MakeKuiklyComposeNode(
        factory = { view },
        modifier = modifier,
        viewInit = {
            getViewEvent().liveEvent { if (acceptingEvents[0]) currentEvent(it) }
            getViewAttr().room(liveId, preview, active)
            submitted[0] = room
        },
        viewUpdate = { node ->
            // 相同 room 重发会重新打开 native explicitRelease 门禁，普通重组不能重启已退出会话。
            if (submitted[0] != room) {
                node.getViewAttr().room(liveId, preview, active)
                submitted[0] = room
            }
        },
    )
}
