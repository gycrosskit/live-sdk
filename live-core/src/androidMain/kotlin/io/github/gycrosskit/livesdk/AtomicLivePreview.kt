package io.github.gycrosskit.livesdk

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import com.tencent.cloud.tuikit.engine.common.TUICommonDefine
import com.tencent.cloud.tuikit.engine.room.TUIRoomDefine
import io.trtc.tuikit.atomicxcore.api.view.CoreViewType
import io.trtc.tuikit.atomicxcore.api.view.LiveCoreView

/**
 * Android Main 静音预览实现；容器和 Store 生命周期由预览控制器成对管理。
 *
 * @param context 当前页面 Context，在实例存活期间持有。
 * @param liveId 当前实例的腾讯房间 ID，不跨房间复用。
 */
class AtomicLiveCorePreviewPlayback(
    private val context: Context,
    private val liveId: String,
) : AtomicLivePreviewPlayback {
    /** 宿主挂载的轻量容器，停止时移除 SDK 子 View 以释放 Store 监听。 */
    val container = FrameLayout(context)
    private var view: LiveCoreView? = null

    override fun start(liveId: String, callback: AtomicLivePreviewPlayback.Callback) {
        val previewView = view ?: createView()
        previewView.startPreviewLiveStream(
            liveId,
            true,
            object : TUIRoomDefine.PlayCallback {
                override fun onPlaying(userId: String) {
                    dispatch(callback::onPlaying)
                }

                override fun onLoading(userId: String) {
                    dispatch(callback::onLoading)
                }

                override fun onPlayError(
                    userId: String,
                    error: TUICommonDefine.Error,
                    message: String,
                ) {
                    dispatch { callback.onFailure(error.value, message) }
                }
            },
        )
    }

    override fun stopAndDetach(liveId: String) {
        val releasedView = view ?: return
        view = null
        try {
            releasedView.stopPreviewLiveStream(liveId)
        } finally {
            // stopPreviewLiveStream 只停止预加载，不会取消 LiveCoreView 对进程级 Store 的监听。
            // 必须同步移除 View 触发 onDetachedFromWindow，否则它会在完整进房后把渲染目标抢回后台列表窗口。
            (releasedView.parent as? ViewGroup)?.removeView(releasedView)
        }
    }

    private fun createView(): LiveCoreView = LiveCoreView(
        context,
        null,
        0,
        CoreViewType.PLAY_VIEW,
    ).apply {
        setLiveID(liveId)
        container.addView(
            this,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        view = this
    }

    private fun dispatch(block: () -> Unit) = AtomicMainThread.run(block)
}
