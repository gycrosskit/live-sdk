package io.github.gycrosskit.livesdk

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier

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
    val bridge = IosLiveSdkRuntime.bridgeFlow.collectAsState().value
    if (bridge == null) {
        LaunchedEffect(request.liveId) { request.onStateChanged(LivePreviewState.COVER) }
        return
    }
    IosAtomicLivePreview(
        bridge = bridge,
        liveId = request.liveId,
        active = request.active && IosLiveSdkRuntime.sessionReadyFlow.collectAsState().value,
        modifier = modifier,
        onStateChanged = request.onStateChanged,
    )
}

@Composable
private fun PlaybackContent(
    request: LiveCoreViewRequest.Playback,
    modifier: Modifier,
) {
    val bridge = IosLiveSdkRuntime.bridgeFlow.collectAsState().value
    val currentCallbacks by rememberUpdatedState(request.callbacks)
    if (bridge == null) {
        LaunchedEffect(request.liveId) {
            currentCallbacks.onJoinFailed(BRIDGE_NOT_INSTALLED_CODE, BRIDGE_NOT_INSTALLED_MESSAGE)
        }
        return
    }
    val view = remember(bridge, request.liveId, request.likeReporter) {
        IosAtomicAudienceView(
            bridge = bridge,
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
    val host = remember(view) { platform.UIKit.UIView().also(view::mount) }
    // factory 只在节点创建时执行；换房后需要重新挂载对应的原生 View。
    key(view) {
        androidx.compose.ui.viewinterop.UIKitView(factory = { host }, modifier = modifier, properties = PassiveLiveViewProperties)
    }
}

private const val BRIDGE_NOT_INSTALLED_CODE = -1
private const val BRIDGE_NOT_INSTALLED_MESSAGE = "iOS live bridge is not installed"
