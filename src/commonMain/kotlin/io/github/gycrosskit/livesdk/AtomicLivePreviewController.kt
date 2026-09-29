package io.github.gycrosskit.livesdk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 隔离预览播放实现，使 active、页面可见性和迟到回调规则可以在 JVM 单测中验证。
 */
internal interface AtomicLivePreviewPlayback {
    interface Callback {
        fun onLoading()

        fun onPlaying()

        fun onFailure(code: Int, message: String)
    }

    fun start(liveId: String, callback: Callback)

    /** 停止预览并释放原生 View 的 Store 监听，保证完整直播间可以独占渲染目标。 */
    fun stopAndDetach(liveId: String)
}

/** 完整直播间占用共享 Store 时，预览必须等待离房完成后才能真正启动。 */
internal interface AtomicLivePreviewStartGate {
    fun request(token: Any, start: () -> Unit)

    fun cancel(token: Any)

    object Immediate : AtomicLivePreviewStartGate {
        override fun request(token: Any, start: () -> Unit) = start()

        override fun cancel(token: Any) = Unit
    }
}

/**
 * 列表预览生命周期状态机。
 *
 * 只有列表项 active 且页面至少 STARTED 时才播放；任何一项条件失效都会立即停止。generation 用于拒绝
 * stop/restart 之前的 SDK 迟到回调，release 可被生命周期和 Composition 销毁路径重复调用。
 */
internal class AtomicLivePreviewController(
    private val liveId: String,
    private val playback: AtomicLivePreviewPlayback,
    private val onStateChanged: (LivePreviewState) -> Unit,
    private val startGate: AtomicLivePreviewStartGate = AtomicLivePreviewStartGate.Immediate,
) {
    private var active = false
    private var lifecycleStarted = false
    private var previewing = false
    private var startPending = false
    private var released = false
    private var generation = 0L
    private var state = LivePreviewState.COVER
    private val startToken = Any()

    fun currentState(): LivePreviewState = state

    fun setActive(active: Boolean) {
        if (released || this.active == active) return
        this.active = active
        reconcile()
    }

    fun setLifecycleStarted(started: Boolean) {
        if (released || lifecycleStarted == started) return
        lifecycleStarted = started
        reconcile()
    }

    fun release() {
        if (released) return
        released = true
        stop(LivePreviewState.COVER)
    }

    /**
     * 完整直播间进房前同步停止当前预览。
     *
     * 不改变 [active] 与页面生命周期；AppHost 真正进入后台后会收到 STOP，返回列表再次 START 时才重新播放。
     * 当前调用已经把 previewing 清除，后续 onDispose 不会再重复调用 SDK stop 误伤新会话。
     */
    internal fun stopBeforeFullSession() {
        if (released) return
        stop(LivePreviewState.COVER)
    }

    private fun reconcile() {
        if (active && lifecycleStarted && liveId.isNotBlank()) {
            startIfNeeded()
        } else {
            stop(LivePreviewState.COVER)
        }
    }

    private fun startIfNeeded() {
        if (previewing || startPending || released) return
        startPending = true
        startGate.request(startToken) {
            startPending = false
            if (!released && active && lifecycleStarted && liveId.isNotBlank()) {
                startPlayback()
            }
        }
    }

    private fun startPlayback() {
        if (previewing || released) return
        AtomicLivePreviewSessionCoordinator.activate(
            controller = this,
            stop = ::stopBeforeFullSession,
        )
        previewing = true
        val callbackGeneration = ++generation
        publish(LivePreviewState.LOADING)
        try {
            playback.start(
                liveId,
                object : AtomicLivePreviewPlayback.Callback {
                    override fun onLoading() {
                        if (accepts(callbackGeneration)) publish(LivePreviewState.LOADING)
                    }

                    override fun onPlaying() {
                        if (accepts(callbackGeneration)) publish(LivePreviewState.PLAYING)
                    }

                    override fun onFailure(code: Int, message: String) {
                        if (!accepts(callbackGeneration)) return
                        AtomicAudienceRuntimeRegistry.warning(
                            "AtomicX 列表预览失败，liveId=$liveId, code=$code, message=$message",
                        )
                        stop(LivePreviewState.FAILED)
                    }
                },
            )
        } catch (throwable: Throwable) {
            if (accepts(callbackGeneration)) {
                AtomicAudienceRuntimeRegistry.error(
                    "AtomicX 列表预览启动异常，liveId=$liveId",
                    throwable,
                )
                stop(LivePreviewState.FAILED)
            }
        }
    }

    private fun stop(nextState: LivePreviewState) {
        generation += 1L
        startGate.cancel(startToken)
        startPending = false
        AtomicLivePreviewSessionCoordinator.deactivate(this)
        if (previewing) {
            previewing = false
            try {
                playback.stopAndDetach(liveId)
            } catch (throwable: Throwable) {
                AtomicAudienceRuntimeRegistry.error(
                    "AtomicX 列表预览停止异常，liveId=$liveId",
                    throwable,
                )
            }
        }
        publish(nextState)
    }

    private fun accepts(callbackGeneration: Long): Boolean =
        !released && previewing && generation == callbackGeneration && active && lifecycleStarted

    private fun publish(nextState: LivePreviewState) {
        if (state == nextState) return
        state = nextState
        onStateChanged(nextState)
    }
}

/**
 * 进程内唯一活动列表预览的所有权。
 *
 * shared 正常只组合一个 renderer；这里再在 SDK 边界保证完整直播间能在 joinLive 前同步停止旧预览，避免
 * Activity 生命周期回调晚到时对新播放产生竞态。
 */
internal object AtomicLivePreviewSessionCoordinator {
    private var activeController: AtomicLivePreviewController? = null
    private var activeStop: (() -> Unit)? = null

    fun activate(controller: AtomicLivePreviewController, stop: () -> Unit) {
        if (activeController === controller) return
        val previousStop = activeStop
        activeController = controller
        activeStop = stop
        previousStop?.invoke()
    }

    fun deactivate(controller: AtomicLivePreviewController) {
        if (activeController !== controller) return
        activeController = null
        activeStop = null
    }

    fun stopActivePreview() {
        val stop = activeStop
        activeController = null
        activeStop = null
        stop?.invoke()
    }
}

/** 完整直播间或账号重置在启动前用于同步停止当前双端列表预览。 */
object AtomicLivePreviewRuntime {
    fun stopActivePreview() {
        AtomicLivePreviewSessionCoordinator.stopActivePreview()
    }
}

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


/** 把双端预览启动接入完整观看会话队列，离房完成前不得重新占用 AtomicX 共享 Store。 */
internal object AtomicAudienceLivePreviewStartGate : AtomicLivePreviewStartGate {
    override fun request(token: Any, start: () -> Unit) {
        AtomicAudienceSessionCoordinator.acquirePreview(token, start)
    }

    override fun cancel(token: Any) {
        AtomicAudienceSessionCoordinator.cancelPreview(token)
    }
}
