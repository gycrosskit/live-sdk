package io.github.gycrosskit.livesdk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import platform.UIKit.UIView

internal val PassiveLiveViewProperties = UIKitInteropProperties(
    isInteractive = false,
    isNativeAccessibilityEnabled = false,
)

/** iOS 列表预览控件；shared 只消费中立状态，不再直接创建或释放 `UIView`。 */
@OptIn(ExperimentalForeignApi::class)
@Composable
internal fun IosAtomicLivePreview(
    bridge: IosLiveSdkBridge,
    liveId: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onStateChanged: (LivePreviewState) -> Unit = {},
) {
    var nativeView by remember(liveId) { mutableStateOf<UIView?>(null) }
    val playback = remember(bridge, liveId) {
        IosAtomicLivePreviewPlayback(
            bridge = bridge,
            onViewChanged = { nativeView = it },
        )
    }
    rememberAtomicLivePreviewController(liveId, active, playback, onStateChanged)
    nativeView?.let { view ->
        UIKitView(
            factory = { view },
            modifier = modifier,
            onRelease = playback::releaseView,
            // 列表卡片点击属于上层 CMP；原生预览只渲染画面，不能截断父级点击手势。
            properties = PassiveLiveViewProperties,
        )
    }
}
