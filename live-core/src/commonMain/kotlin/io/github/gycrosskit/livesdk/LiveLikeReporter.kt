package io.github.gycrosskit.livesdk

/** 将 SDK 点赞成功事件桥接给 App 的业务任务上报。 */
fun interface LiveLikeReporter {
    /**
     * 上报已被原生 SDK 接受的点赞批次。
     *
     * [liveId] 是腾讯直播间 ID，[userId] 是当前直播账号，[count] 是本批点赞数。
     */
    fun report(liveId: String, userId: String, count: Int)

    companion object {
        /** 仅供 Debug 预览等不连接 App 业务层的组件实例使用。 */
        val None = LiveLikeReporter { _, _, _ -> Unit }
    }
}
