package io.github.gycrosskit.livesdk

/** shared 账号准备流程只同步可播放门禁，不参与具体 View 创建。 */
object AndroidLiveSdkRuntime {
    /** 当前账号可播放门禁；只代表宿主已完成账号准备，不代表某房间已进房。 */
    val isSessionReady: Boolean get() = ready.value

    /** 同步 AtomicX 账号是否已准备完成，未准备时列表预览不得启动。 */
    private val ready = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** Compose/Kuikly 共用的只读账号准备状态流。 */
    val sessionReadyFlow: kotlinx.coroutines.flow.StateFlow<Boolean> get() = ready

    /**
     * 宿主登录/注销流程同步账号准备结果，不能由播放成功代替。
     *
     * @param ready 账号准备結果；默认 false，注销或换账号前应清为 false。
     */
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
