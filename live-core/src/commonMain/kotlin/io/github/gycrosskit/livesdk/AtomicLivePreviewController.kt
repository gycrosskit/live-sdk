package io.github.gycrosskit.livesdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 隔离预览播放实现，使 active、页面可见性和迟到回调规则可以在 JVM 单测中验证。
 */
interface AtomicLivePreviewPlayback {
    /** Main 上交付的原生预览结果；停止后的结果由控制器代次门禁丢弃。 */
    interface Callback {
        /** 流正在准备或缓冲，尚未确认播放。 */
        fun onLoading()

        /** SDK 已确认预览流开始播放。 */
        fun onPlaying()

        /**
         * SDK 播放失败，错误码与描述仅用于诊断。
         *
         * @param code 平台错误码，组件自身失败可使用负值。
         * @param message 原始事件文本，展示与脱敏策略由宿主决定。
         */
        fun onFailure(code: Int, message: String)
    }

    /**
     * 在 Main 创建/启动 [liveId] 的静音预览，不进入完整直播间。
     *
     * @param liveId 当前实例的腾讯房间 ID，不跨房间复用。
     * @param callback 当前操作完成回执，Native 结果在 Main 交付。
     */
    fun start(liveId: String, callback: Callback)

    /**
     * 停止预览并释放原生 View 的 Store 监听，保证完整直播间可以独占渲染目标。
     *
     * @param liveId 当前实例的腾讯房间 ID，不跨房间复用。
     */
    fun stopAndDetach(liveId: String)
}

/** 完整直播间占用共享 Store 时，预览必须等待离房完成后才能真正启动。 */
interface AtomicLivePreviewStartGate {
    /**
     * 申请 [token] 的启动权；可能等待完整观看离房后才调用 [start]。
     *
     * @param token 按对象身份区分的启动申请，取消时须传同一对象。
     * @param start 获得共享会话执行权后在 Main 调用的启动动作。
     */
    fun request(token: Any, start: () -> Unit)

    /**
     * 撤销尚未启动的申请，不能停止其他 token 的预览。
     *
     * @param token 按对象身份区分的启动申请，取消时须传同一对象。
     */
    fun cancel(token: Any)

    /** 无共享 SDK 争用的替身或独立实现直接放行。 */
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
 * 创建、全部命令和播放回调必须在 Main 串行执行。
 *
 * @param liveId 当前实例的固定直播 ID；空白 ID 保持封面状态。
 * @param playback 平台静音预览和 View 清理实现。
 * @param onStateChanged 仅状态变化时在 Main 通知宿主。
 * @param startGate 完整观看会话的共享 Store 门禁。
 */
class AtomicLivePreviewController(
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

    /** 当前已确认状态；初始为 COVER，释放后回到 COVER。 */
    fun currentState(): LivePreviewState = state

    /**
     * 同步列表项是否被选为活动项；失活立即停止播放。
     *
     * @param active 业务激活标记，false 停止播放。
     */
    fun setActive(active: Boolean) {
        if (released || this.active == active) return
        this.active = active
        reconcile()
    }

    /**
     * 同步页面是否至少 STARTED；后台停止，前台按 active 决定是否恢复。
     *
     * @param started 页面是否至少 STARTED，默认 false。
     */
    fun setLifecycleStarted(started: Boolean) {
        if (released || lifecycleStarted == started) return
        lifecycleStarted = started
        reconcile()
    }

    /** 幂等停止并撤回等待；之后不可重新激活此实例。 */
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

/** 同步入口仅供 Main 使用；Renderer/后台调用须等待 [stopActivePreviewAndAwait] 完成后再操作 SDK。 */
object AtomicLivePreviewRuntime {
    /** Main 同步停止当前唯一活动预览，返回前完成原生 View 的 detach。 */
    fun stopActivePreview() {
        AtomicLivePreviewSessionCoordinator.stopActivePreview()
    }

    /** 后台或 Renderer 挂起等待 Main 停止完成，再执行后续 SDK 操作。 */
    suspend fun stopActivePreviewAndAwait() = withContext(Dispatchers.Main.immediate) {
        stopActivePreview()
    }
}

/** 把双端预览启动接入完整观看会话队列，离房完成前不得重新占用 AtomicX 共享 Store。 */
object AtomicAudienceLivePreviewStartGate : AtomicLivePreviewStartGate {
    override fun request(token: Any, start: () -> Unit) {
        AtomicAudienceSessionCoordinator.acquirePreview(token, start)
    }

    override fun cancel(token: Any) {
        AtomicAudienceSessionCoordinator.cancelPreview(token)
    }
}
