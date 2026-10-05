package io.github.gycrosskit.livesdk

/** 将 SDK 点赞成功事件桥接给 App 的业务任务上报。 */
fun interface LiveLikeReporter {
    /**
     * 上报已被原生 SDK 接受的点赞批次。
     *
     * @param liveId 本批点赞归属的腾讯直播间 ID。
     * @param userId 本批提交时的腾讯直播账号 ID。
     * @param count 本批点赞数，单位为次，应大于 0。
     */
    fun report(liveId: String, userId: String, count: Int)

    companion object {
        /** 仅供 Debug 预览等不连接 App 业务层的组件实例使用。 */
        val None = LiveLikeReporter { _, _, _ -> Unit }
    }
}
