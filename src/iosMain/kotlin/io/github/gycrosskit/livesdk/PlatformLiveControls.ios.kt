package io.github.gycrosskit.livesdk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** iOS actual 创建原生 View 所需的唯一桥接安装点。 */
object IosLiveSdkRuntime {
    internal var bridge by mutableStateOf<IosLiveSdkBridge?>(null)
        private set
    internal var sessionReady by mutableStateOf(false)
        private set

    /** 安装 iosApp 提供的 Swift SDK 桥，应在组合任何直播控件前调用。 */
    fun install(bridge: IosLiveSdkBridge) {
        this.bridge = bridge
    }

    /** 同步 AtomicX 账号是否已准备完成，未准备时列表预览不得启动。 */
    fun updateSessionReady(ready: Boolean) {
        sessionReady = ready
    }

    /** 在完整进房或账号重置前停止 Kotlin 预览状态机并让 Swift 幂等清理。 */
    fun stopActivePreview() {
        AtomicLivePreviewRuntime.stopActivePreview()
        // Swift 侧再做一次幂等兜底，覆盖 UIKit 在异常销毁路径中未及时触发 onRelease 的情况。
        bridge?.stopPreview()
    }
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
    val bridge = IosLiveSdkRuntime.bridge
    if (bridge == null) {
        LaunchedEffect(request.liveId) { request.onStateChanged(LivePreviewState.COVER) }
        return
    }
    IosAtomicLivePreview(
        bridge = bridge,
        liveId = request.liveId,
        active = request.active && IosLiveSdkRuntime.sessionReady,
        modifier = modifier,
        onStateChanged = request.onStateChanged,
    )
}

@Composable
private fun PlaybackContent(
    request: LiveCoreViewRequest.Playback,
    modifier: Modifier,
) {
    val bridge = IosLiveSdkRuntime.bridge
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
    view.Player(modifier)
}

private const val BRIDGE_NOT_INSTALLED_CODE = -1
private const val BRIDGE_NOT_INSTALLED_MESSAGE = "iOS live bridge is not installed"
