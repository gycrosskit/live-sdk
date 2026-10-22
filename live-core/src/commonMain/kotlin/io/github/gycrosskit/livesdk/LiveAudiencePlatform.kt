package io.github.gycrosskit.livesdk

/** ArkTS 等无法直接调用 Kotlin 类型的平台边界；全部操作与回调在 Main 串行执行。 */
interface LiveAudiencePlatform {
    /** 仅在取得公共会话令牌后调用；结果异步交给 observer。 */
    fun join(liveId: String, observer: LiveAudiencePlatformObserver)
    /** 完成真实离房后回执；平台须隔离旧请求，不能让迟到 leave 影响下一房间。 */
    fun leave(onFinished: (Boolean, Int, String) -> Unit)
    fun sendBarrage(message: String, onFinished: (Boolean, String) -> Unit)
    fun sendLike(count: Int, onFinished: (Boolean) -> Unit)
    fun refreshAudience()
}

/** 平台只转换 SDK 数据；列表去重、消息上限和最终快照由 commonMain 维护。 */
interface LiveAudiencePlatformObserver {
    fun joined(host: LiveAudienceHostSnapshot, introduction: String)
    fun joinFailed(code: Int, message: String)
    fun liveEnded()
    fun kickedOut()
    fun messages(values: List<LiveAudienceMessageSnapshot>)
    fun audience(users: List<LiveAudienceUserSnapshot>, count: Int)
    fun likes(count: Int)
    fun messageDisabled(disabled: Boolean)
}

