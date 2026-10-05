@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.gycrosskit.livesdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSThread

/** iOS actual 创建原生 View 所需的唯一桥接安装点。 */
object IosLiveSdkRuntime {
    /** 当前账号可播放门禁；只代表宿主已完成账号准备，不代表某房间已进房。 */
    val isSessionReady: Boolean get() = ready.value
    private val installedBridge = kotlinx.coroutines.flow.MutableStateFlow<IosLiveSdkBridge?>(null)
    /** 已安装的 Swift 原生桥；未安装为 null。 */
    val bridge: IosLiveSdkBridge? get() = installedBridge.value
    /** 桥安装变化，供声明式控件延迟创建原生 View。 */
    val bridgeFlow: kotlinx.coroutines.flow.StateFlow<IosLiveSdkBridge?> get() = installedBridge

    /**
     * 安装 iosApp 提供的 Swift SDK 桥，应在组合任何直播控件前调用。
     *
     * @param bridge 已安装的 Swift 原生桥，不归本实例注销。
     */
    fun install(bridge: IosLiveSdkBridge) {
        installedBridge.value = bridge
    }

    /** 同步 AtomicX 账号是否已准备完成，未准备时列表预览不得启动。 */
    private val ready = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** Compose/Kuikly 共用的只读账号准备状态流。 */
    val sessionReadyFlow: kotlinx.coroutines.flow.StateFlow<Boolean> get() = ready

    /**
     * 宿主登录/注销流程同步账号准备结果，不能由播放成功代替。
     *
     * @param ready 宿主账号准备结果；默认 false，注销或切换身份前应清为 false。
     */
    fun updateSessionReady(ready: Boolean) {
        this.ready.value = ready
    }

    /** Main 同步停止 Kotlin 预览状态机并让 Swift 幂等清理。 */
    fun stopActivePreview() {
        check(NSThread.isMainThread) { "Live SDK synchronous UI operation requires Main" }
        AtomicLivePreviewRuntime.stopActivePreview()
        // Swift 侧再做一次幂等兜底，覆盖 UIKit 在异常销毁路径中未及时触发 onRelease 的情况。
        bridge?.stopPreview()
    }

    /** Renderer/后台挂起等待 Main 完成，不能阻塞 Main 与 Kuikly ContextQueue 的互调。 */
    suspend fun stopActivePreviewAndAwait() = withContext(Dispatchers.Main.immediate) {
        stopActivePreview()
    }
}
