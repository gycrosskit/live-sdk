package io.github.gycrosskit.livesdk

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

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
        active = request.active && AndroidLiveSdkRuntime.sessionReadyFlow.collectAsState().value,
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
    // factory 只在节点创建时执行；换房后需要重新挂载对应的原生 View。
    key(view) {
        androidx.compose.ui.viewinterop.AndroidView(factory = { view.nativeView }, modifier = modifier)
    }
}
