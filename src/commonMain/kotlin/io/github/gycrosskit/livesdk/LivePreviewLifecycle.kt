package io.github.gycrosskit.livesdk
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 双端共用的声明式预览生命周期绑定。
 *
 * 平台 actual 只提供原生 [AtomicLivePreviewPlayback]；active、前后台、Composition 释放以及迟到回调规则
 * 全部在这里维护，避免 Android/iOS 对同一列表卡片产生不同的播放时机。
 */
@Composable
internal fun rememberAtomicLivePreviewController(
    liveId: String,
    active: Boolean,
    playback: AtomicLivePreviewPlayback,
    onStateChanged: (LivePreviewState) -> Unit,
): AtomicLivePreviewController {
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnStateChanged by rememberUpdatedState(onStateChanged)
    val controller = remember(liveId, playback) {
        AtomicLivePreviewController(
            liveId = liveId,
            playback = playback,
            onStateChanged = { latestOnStateChanged(it) },
            startGate = AtomicAudienceLivePreviewStartGate,
        )
    }

    LaunchedEffect(controller) {
        if (controller.currentState() == LivePreviewState.COVER) {
            latestOnStateChanged(LivePreviewState.COVER)
        }
    }
    SideEffect { controller.setActive(active) }
    DisposableEffect(controller, lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, _ ->
            controller.setLifecycleStarted(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
        lifecycle.addObserver(observer)
        controller.setLifecycleStarted(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            lifecycle.removeObserver(observer)
            controller.setLifecycleStarted(false)
        }
    }
    DisposableEffect(controller) {
        onDispose(controller::release)
    }
    return controller
}
