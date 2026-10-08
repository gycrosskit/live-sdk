package android.os
class Looper private constructor() {
    companion object {
        private val main = Looper()
        private val mainThread = Thread.currentThread()
        fun getMainLooper() = main
        fun myLooper() = if (Thread.currentThread() == mainThread) main else null
    }
}
// 本 fixture 只驱动 Main 上的账号时序；真实 Android Looper 由 Robolectric 验证。
class Handler(looper: Looper) {
    fun post(block: () -> Unit): Boolean = error("Unexpected off-main dispatch")
}
