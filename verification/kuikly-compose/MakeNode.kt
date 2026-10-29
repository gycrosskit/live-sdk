package com.tencent.kuikly.compose.extension

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import com.tencent.kuikly.compose.ui.Modifier
import io.github.gycrosskit.livesdk.KuiklyLiveView

@Composable
fun MakeKuiklyComposeNode(factory: () -> KuiklyLiveView, modifier: Modifier,
    viewInit: KuiklyLiveView.() -> Unit, viewUpdate: (KuiklyLiveView) -> Unit = {}) {
    val node = remember { factory() }
    val initialized = remember { booleanArrayOf(false) }
    SideEffect {
        if (!initialized[0]) { initialized[0] = true; node.viewInit() } else viewUpdate(node)
    }
}
