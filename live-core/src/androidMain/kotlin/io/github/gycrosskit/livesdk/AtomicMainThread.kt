package io.github.gycrosskit.livesdk

import android.os.Handler
import android.os.Looper

/**
 * AtomicX/IM 回调进入 Compose 状态前的统一主线程边界。
 *
 * 腾讯不同 Store 的回调线程约定并不完全一致；模块内统一经此处调度，避免每个控制器各自创建
 * `Handler`，也避免后台回调直接修改 Snapshot 状态。需要规避 SDK 同步重入时使用 [post]，仅需保证
 * 当前位于主线程时使用 [run]。
 */
internal object AtomicMainThread {
    private val handler = Handler(Looper.getMainLooper())

    fun run(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Live SDK synchronous UI operation requires Main" }
    }

    fun post(block: () -> Unit) {
        handler.post(block)
    }

}
