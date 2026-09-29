package io.github.gycrosskit.livesdk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/** shared 账号准备流程只同步可播放门禁，不参与具体 View 创建。 */
object AndroidLiveSdkRuntime {
    internal var sessionReady by mutableStateOf(false)
        private set

    /** 同步 AtomicX 账号是否已准备完成，未准备时列表预览不得启动。 */
    fun updateSessionReady(ready: Boolean) {
        sessionReady = ready
    }

    /** 在完整进房或账号重置前幂等停止当前列表预览。 */
    fun stopActivePreview() = AtomicLivePreviewRuntime.stopActivePreview()
}

@Composable
internal actual fun PlatformLiveCoreView(
    request: LiveCoreViewRequest,
    modifier: Modifier,
) {
    when (request) {
        is LiveCoreViewRequest.Preview -> PreviewContent(request, modifier)
        is LiveCoreViewRequest.Playback -> PlaybackContent(request, modifier)
    }
}

@Composable
private fun PreviewContent(
    request: LiveCoreViewRequest.Preview,
    modifier: Modifier,
) {
    AtomicLivePreview(
        liveId = request.liveId,
        active = request.active && AndroidLiveSdkRuntime.sessionReady,
        modifier = modifier,
        onStateChanged = request.onStateChanged,
    )
}

@Composable
private fun PlaybackContent(
    request: LiveCoreViewRequest.Playback,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val currentCallbacks by rememberUpdatedState(request.callbacks)
    val view = remember(context, request.liveId, request.likeReporter) {
        AtomicAudienceView(
            context = context,
            liveId = request.liveId,
            listener = LivePlaybackCallbackListener { currentCallbacks },
            likeReporter = request.likeReporter,
        )
    }
    DisposableEffect(view, request.state) {
        request.state.attach(view)
        onDispose {
            request.state.detach(view)
            view.release()
        }
    }
    view.Player(modifier)
}
