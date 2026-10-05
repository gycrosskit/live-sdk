package io.github.gycrosskit.liveconsumer

import android.app.Activity
import android.os.Bundle
import androidx.compose.ui.platform.ComposeView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ComposeView(this).apply { setContent { consumeCmp() } })
    }
}
