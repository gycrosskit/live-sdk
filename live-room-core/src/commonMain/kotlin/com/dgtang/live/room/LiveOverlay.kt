package com.dgtang.live.room

enum class LiveOverlayPriority(val weight: Int) {
    TASK(40),
    USER(60),
    RED_PACKET(70),
    CHECK_IN(80),
    RESULT(90),
    TERMINAL(100),
}

/** 排队仅依赖身份、优先级和单调时钟；内容与业务有效性归调用方。 */
interface LiveOverlayItem {
    val key: String
    val priority: LiveOverlayPriority
    val resumable: Boolean
    val expiresAtElapsedRealtimeMillis: Long?
}

data class LiveOverlayState<out T : LiveOverlayItem>(val active: T? = null, val pending: List<T> = emptyList())

object LiveOverlayReducer {
    /** 新弹层入队；更高优先级会抢占当前弹层，并在允许时保存被抢占项。 */
    fun <T : LiveOverlayItem> enqueue(
        state: LiveOverlayState<T>,
        request: T,
        nowElapsedRealtimeMillis: Long,
    ): LiveOverlayState<T> {
        val current = normalize(state, nowElapsedRealtimeMillis)
        if (request.isExpired(nowElapsedRealtimeMillis)) return current
        val active = current.active ?: return current.copy(active = request)
        val pendingWithoutDuplicate = current.pending.filterNot { it.key == request.key }
        if (active.key == request.key) return current.copy(active = request, pending = pendingWithoutDuplicate)

        return if (request.priority.weight > active.priority.weight) {
            val resumableActive = if (active.resumable && !active.isExpired(nowElapsedRealtimeMillis)) {
                listOf(active)
            } else {
                emptyList()
            }
            current.copy(
                active = request,
                pending = pendingWithoutDuplicate + resumableActive,
            )
        } else {
            current.copy(pending = pendingWithoutDuplicate + request)
        }
    }

    /** 当前操作产生结果时直接替换当前项，不把已经完成的表单或红包重新排队。 */
    fun <T : LiveOverlayItem> replaceActive(
        state: LiveOverlayState<T>,
        replacement: T,
        nowElapsedRealtimeMillis: Long,
    ): LiveOverlayState<T> {
        val current = normalize(state, nowElapsedRealtimeMillis)
        return current.copy(
            active = replacement.takeUnless { it.isExpired(nowElapsedRealtimeMillis) },
            pending = current.pending.filterNot { it.key == replacement.key },
        ).let { normalize(it, nowElapsedRealtimeMillis) }
    }

    /** 关闭当前弹层，并从未过期队列中选取优先级最高、同级最早到达的一项。 */
    fun <T : LiveOverlayItem> dismissActive(
        state: LiveOverlayState<T>,
        nowElapsedRealtimeMillis: Long,
    ): LiveOverlayState<T> = normalize(state.copy(active = null), nowElapsedRealtimeMillis)

    /** 删除指定业务弹层；用于签到结束等服务端主动失效事件。 */
    fun <T : LiveOverlayItem> remove(
        state: LiveOverlayState<T>,
        key: String,
        nowElapsedRealtimeMillis: Long,
    ): LiveOverlayState<T> = normalize(
        state.copy(
            active = state.active?.takeUnless { it.key == key },
            pending = state.pending.filterNot { it.key == key },
        ),
        nowElapsedRealtimeMillis,
    )

    /** 业务自行提供匹配条件，组件不认识业务面板。 */
    fun <T : LiveOverlayItem> removeWhere(
        state: LiveOverlayState<T>,
        nowElapsedRealtimeMillis: Long,
        matches: (T) -> Boolean,
    ): LiveOverlayState<T> = normalize(
        state.copy(active = state.active?.takeUnless(matches), pending = state.pending.filterNot(matches)),
        nowElapsedRealtimeMillis,
    )

    /** 终态或进入画中画时清除全部 App 模态内容，防止恢复后倒灌旧事件。 */
    fun <T : LiveOverlayItem> clear(): LiveOverlayState<T> = LiveOverlayState<T>()

    /** 终态弹层不参与普通排队，直接替换并清空其余内容。 */
    fun <T : LiveOverlayItem> terminal(request: T): LiveOverlayState<T> = LiveOverlayState<T>(active = request)

    private fun <T : LiveOverlayItem> normalize(
        state: LiveOverlayState<T>,
        nowElapsedRealtimeMillis: Long,
    ): LiveOverlayState<T> {
        val active = state.active?.takeUnless { it.isExpired(nowElapsedRealtimeMillis) }
        val pending = state.pending.filterNot { it.isExpired(nowElapsedRealtimeMillis) }
        if (active != null) return LiveOverlayState<T>(active, pending)
        if (pending.isEmpty()) return LiveOverlayState<T>()
        val highestWeight = pending.maxOf { it.priority.weight }
        val nextIndex = pending.indexOfFirst { it.priority.weight == highestWeight }
        return LiveOverlayState<T>(
            active = pending[nextIndex],
            pending = pending.filterIndexed { index, _ -> index != nextIndex },
        )
    }

    private fun LiveOverlayItem.isExpired(nowElapsedRealtimeMillis: Long): Boolean =
        expiresAtElapsedRealtimeMillis?.let { it <= nowElapsedRealtimeMillis } == true
}
