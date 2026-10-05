package io.github.gycrosskit.livesdk

/** shared 账号准备流程只同步可播放门禁，不参与具体 View 创建。 */
object AndroidLiveSdkRuntime {
    val isSessionReady: Boolean get() = ready.value

    /** 同步 AtomicX 账号是否已准备完成，未准备时列表预览不得启动。 */
    private val ready = kotlinx.coroutines.flow.MutableStateFlow(false)
    val sessionReadyFlow: kotlinx.coroutines.flow.StateFlow<Boolean> get() = ready

    fun updateSessionReady(ready: Boolean) {
        this.ready.value = ready
    }

    /** Main 同步停止；返回时 SDK 预览已停止且 View 已移除。 */
    fun stopActivePreview() {
        AtomicMainThread.checkMainThread()
        AtomicLivePreviewRuntime.stopActivePreview()
    }

    /** Renderer/后台可等待停止完成，再执行后续登录或进房。 */
    suspend fun stopActivePreviewAndAwait() = AtomicLivePreviewRuntime.stopActivePreviewAndAwait()
}
