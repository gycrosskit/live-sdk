package io.github.gycrosskit.livesdk

/** 将腾讯进房错误收敛为双端一致的观看语义。 */
internal object LiveJoinFailure {
    // 腾讯直播服务端用 100004 表示房间不存在或已经被解散；观看端应按直播结束处理。
    private const val ROOM_DOES_NOT_EXIST = 100004

    fun isEndedLive(code: Int): Boolean = code == ROOM_DOES_NOT_EXIST
}
