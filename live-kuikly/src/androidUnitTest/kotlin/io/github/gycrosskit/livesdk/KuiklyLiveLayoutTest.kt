package io.github.gycrosskit.livesdk

import android.graphics.Rect
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import com.tencent.kuikly.core.render.android.const.KRCssConst
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class KuiklyLiveLayoutTest {
    @Test fun renderFrameResizesZeroSizedViewAndItsVideoChild() {
        val context = RuntimeEnvironment.getApplication()
        val parent = FrameLayout(context)
        val view = GycLiveView(context)
        val video = TextureView(context)
        // Render 初始尺寸为 0；必须消费真实 FRAME，不能靠宿主设置 MATCH_PARENT 掩盖。
        parent.addView(view, FrameLayout.LayoutParams(0, 0))
        view.addView(video, FrameLayout.LayoutParams(-1, -1))
        assertEquals(0, view.layoutParams.width)
        assertEquals(0, view.layoutParams.height)

        // Kuikly 的 Rect.right/bottom 表示宽高，第二次 frame 覆盖旋转/窗口缩放。
        for (frame in listOf(Rect(12, 24, 320, 180), Rect(8, 16, 180, 320))) {
            assertTrue(view.setProp(KRCssConst.FRAME, frame))
            parent.measure(
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            )
            parent.layout(0, 0, 800, 600)
            assertEquals(frame.left, view.left)
            assertEquals(frame.top, view.top)
            assertEquals(frame.right, view.width)
            assertEquals(frame.bottom, view.height)
            assertEquals(view.width, video.width)
            assertEquals(view.height, video.height)
        }
        assertTrue(view.setProp(KRCssConst.OPACITY, 0.5f))
        assertEquals(0.5f, view.alpha)
        assertFalse(view.setProp("unknownLiveProperty", "ignored"))
    }
}
