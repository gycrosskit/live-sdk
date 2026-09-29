package io.github.gycrosskit.livesdk

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.tencent.cloud.tuikit.engine.common.TUICommonDefine
import com.tencent.cloud.tuikit.engine.room.TUIRoomDefine
import io.trtc.tuikit.atomicxcore.api.view.CoreViewType
import io.trtc.tuikit.atomicxcore.api.view.LiveCoreView

/**
 * AtomicX 房间外列表预览。
 *
 * 控件只负责静音预览，不调用 `joinLive`，也不创建弹幕、点赞或观众 Store。调用方应把 [modifier] 放入有界
 * 媒体容器，并根据 [onStateChanged] 在其上层展示网络封面、Loading 或失败兜底。只有 [active] 且页面处于
 * STARTED 时播放；页面进入后台、列表项失活或离开 Composition 都会停止并幂等释放。
 */
@Composable
internal fun AtomicLivePreview(
    liveId: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onStateChanged: (LivePreviewState) -> Unit = {},
) {
    val context = LocalContext.current
    val player = remember(liveId) { AtomicLiveCorePreviewPlayback(context, liveId) }
    rememberAtomicLivePreviewController(liveId, active, player, onStateChanged)

    key(liveId) {
        AndroidView(
            factory = { player.container },
            modifier = modifier,
        )
    }
}

private class AtomicLiveCorePreviewPlayback(
    private val context: Context,
    private val liveId: String,
) : AtomicLivePreviewPlayback {
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
