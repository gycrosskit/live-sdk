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

    /** 在完整进房或账号重置前幂等停止当前列表预览。 */
    fun stopActivePreview() = AtomicLivePreviewRuntime.stopActivePreview()
}
