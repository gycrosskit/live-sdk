package io.github.gycrosskit.livesdk

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * AtomicX 房间外列表预览。
 *
 * 控件只负责静音预览，不调用 `joinLive`，也不创建弹幕、点赞或观众 Store。调用方应把 [modifier] 放入有界
 * 媒体容器，并根据 [onStateChanged] 在其上层展示网络封面、Loading 或失败兜底。只有 [active] 且页面处于
 * STARTED 时播放；页面进入后台、列表项失活或离开 Composition 都会停止并幂等释放。
 */
@Composable
internal fun AtomicLivePreview(
    liveId: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onStateChanged: (LivePreviewState) -> Unit = {},
) {
    val context = LocalContext.current
    val player = remember(liveId) { AtomicLiveCorePreviewPlayback(context, liveId) }
    rememberAtomicLivePreviewController(liveId, active, player, onStateChanged)

    key(liveId) {
        AndroidView(
            factory = { player.container },
            modifier = modifier,
        )
    }
}
