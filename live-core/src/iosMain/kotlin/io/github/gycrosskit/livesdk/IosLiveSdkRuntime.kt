@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.gycrosskit.livesdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSThread

/** iOS actual 创建原生 View 所需的唯一桥接安装点。 */
object IosLiveSdkRuntime {
    val isSessionReady: Boolean get() = ready.value
    private val installedBridge = kotlinx.coroutines.flow.MutableStateFlow<IosLiveSdkBridge?>(null)
    val bridge: IosLiveSdkBridge? get() = installedBridge.value
    val bridgeFlow: kotlinx.coroutines.flow.StateFlow<IosLiveSdkBridge?> get() = installedBridge

    /** 安装 iosApp 提供的 Swift SDK 桥，应在组合任何直播控件前调用。 */
    fun install(bridge: IosLiveSdkBridge) {
        installedBridge.value = bridge
    }

    /** 同步 AtomicX 账号是否已准备完成，未准备时列表预览不得启动。 */
    private val ready = kotlinx.coroutines.flow.MutableStateFlow(false)
    val sessionReadyFlow: kotlinx.coroutines.flow.StateFlow<Boolean> get() = ready

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
