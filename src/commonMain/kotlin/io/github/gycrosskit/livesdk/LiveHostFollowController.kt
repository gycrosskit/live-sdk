package io.github.gycrosskit.livesdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 平台 IM 关注接口；实现必须把回调派发到 UI 主线程。 */
internal interface LiveHostFollowGateway {
    val currentUserId: String

    fun checkFollowed(userId: String, callback: (LiveFollowResult<Boolean>) -> Unit)

    fun updateFollow(userId: String, followed: Boolean, callback: (LiveFollowResult<Unit>) -> Unit)

    fun fetchFans(userId: String, callback: (LiveFollowResult<Long>) -> Unit)
}

/** 不把 Android V2TIM 或 Swift Result 类型带入共用关注状态机。 */
internal sealed interface LiveFollowResult<out T> {
    data class Success<T>(val value: T) : LiveFollowResult<T>

    data class Failure(val message: String, val error: Throwable? = null) : LiveFollowResult<Nothing>
}

/**
 * 双端共用的主播关注状态机。
 *
 * ownerGeneration、followGeneration 与 fansGeneration 分别隔离切房、关注写入和粉丝数刷新；即使主播 ID
 * 再次相同或同一主播的请求乱序返回，旧结果也不会覆盖新状态。平台层只负责调用 IM API 并返回中立结果。
 */
internal class LiveHostFollowController(
    private val gateway: LiveHostFollowGateway,
    private val scope: CoroutineScope,
    private val onStateChanged: (
        visible: Boolean,
        followed: Boolean,
        requestRunning: Boolean,
        fansCount: Long,
    ) -> Unit = { _, _, _, _ -> },
    private val onFailure: (String) -> Unit = {},
) {
    private var ownerId = ""
    private var ownerGeneration = 0L
    private var followGeneration = 0L
    private var fansGeneration = 0L
    private var released = false

    // 这些字段只用于状态机决策；Compose 的唯一可观察状态由 onStateChanged 写入快照 Store。
    private var visible = false
    private var followed = false
    private var requestRunning = false
    private var requestTimeout: Job? = null
    private var fansCount = 0L

    /** 绑定新主播并使上一主播尚未返回的 IM 请求失效。 */
    fun bind(newOwnerId: String) {
        if (released || newOwnerId == ownerId) return
        ownerId = newOwnerId
        requestTimeout?.cancel()
        ownerGeneration += 1
        followGeneration += 1
        fansGeneration += 1
        visible = newOwnerId.isNotBlank() && newOwnerId != gateway.currentUserId
        followed = false
        requestRunning = false
        fansCount = 0L
        publish()
        if (visible) {
            checkFollowState(newOwnerId, ownerGeneration)
            refreshFans()
        }
    }

    fun toggleFollow() {
        val targetOwnerId = ownerId
        val targetOwnerGeneration = ownerGeneration
        if (!visible || targetOwnerId.isBlank() || requestRunning) return
        val targetFollowed = !followed
        followGeneration += 1
        val targetFollowGeneration = followGeneration
        requestRunning = true
        publish()
        val operation = "${if (targetFollowed) "关注" else "取消关注"}主播"
        requestTimeout = scope.launch {
            delay(10_000L)
            if (!accepts(targetOwnerId, targetOwnerGeneration) || targetFollowGeneration != followGeneration) {
                return@launch
            }
            // 回调未到不能推测服务端结果；保留已知状态，重试仍设置相同目标，旧回调不能再覆盖它。
            followGeneration += 1
            requestRunning = false
            requestTimeout = null
            deliverFailure(
                operation,
                targetOwnerId,
                LiveFollowResult.Failure("", IllegalStateException("IM follow callback timed out")),
            )
        }
        runRequest(
            targetOwnerId = targetOwnerId,
            targetOwnerGeneration = targetOwnerGeneration,
            onRequestFailure = { result ->
                if (targetFollowGeneration == followGeneration) {
                    requestTimeout?.cancel()
                    requestRunning = false
                    deliverFailure(operation, targetOwnerId, result)
                }
            },
        ) {
            gateway.updateFollow(targetOwnerId, targetFollowed) { result ->
                if (
                    !accepts(targetOwnerId, targetOwnerGeneration) ||
                    targetFollowGeneration != followGeneration
                ) {
                    return@updateFollow
                }
                requestTimeout?.cancel()
                requestRunning = false
                when (result) {
                    is LiveFollowResult.Success -> {
                        followed = targetFollowed
                        publish()
                        refreshFans()
                    }

                    is LiveFollowResult.Failure -> deliverFailure(operation, targetOwnerId, result)
                }
            }
        }
    }

    /** 刷新失败保留既有值，避免短暂 IM 波动让弹层数字回退为零。 */
    fun refreshFans() {
        val targetOwnerId = ownerId
        val targetOwnerGeneration = ownerGeneration
        if (!visible || targetOwnerId.isBlank()) return
        fansGeneration += 1
        val targetFansGeneration = fansGeneration
        runRequest(
            targetOwnerId = targetOwnerId,
            targetOwnerGeneration = targetOwnerGeneration,
            onRequestFailure = { result ->
                if (targetFansGeneration == fansGeneration) {
                    logFailure("获取主播粉丝数", targetOwnerId, result)
                }
            },
        ) {
            gateway.fetchFans(targetOwnerId) { result ->
                if (
                    !accepts(targetOwnerId, targetOwnerGeneration) ||
                    targetFansGeneration != fansGeneration
                ) {
                    return@fetchFans
                }
                when (result) {
                    is LiveFollowResult.Success -> {
                        fansCount = result.value
                        publish()
                    }

                    is LiveFollowResult.Failure -> logFailure("获取主播粉丝数", targetOwnerId, result)
                }
            }
        }
    }

    private fun checkFollowState(targetOwnerId: String, targetOwnerGeneration: Long) {
        val targetFollowGeneration = followGeneration
        runRequest(
            targetOwnerId = targetOwnerId,
            targetOwnerGeneration = targetOwnerGeneration,
            onRequestFailure = { result ->
                if (targetFollowGeneration == followGeneration) {
                    deliverFailure("检查主播关注状态", targetOwnerId, result)
                }
            },
        ) {
            gateway.checkFollowed(targetOwnerId) { result ->
                if (
                    !accepts(targetOwnerId, targetOwnerGeneration) ||
                    targetFollowGeneration != followGeneration
                ) {
                    return@checkFollowed
                }
                when (result) {
                    is LiveFollowResult.Success -> {
                        followed = result.value
                        publish()
                    }

                    is LiveFollowResult.Failure -> deliverFailure(
                        "检查主播关注状态",
                        targetOwnerId,
                        result,
                    )
                }
            }
        }
    }

    /** 退出直播间后清空可观察状态，并让全部未完成 IM 回调失效，避免快照短暂残留上一主播数据。 */
    fun release() {
        requestTimeout?.cancel()
        requestTimeout = null
        released = true
        ownerId = ""
        ownerGeneration += 1
        followGeneration += 1
        fansGeneration += 1
        visible = false
        followed = false
        requestRunning = false
        fansCount = 0L
        publish()
    }

    private inline fun runRequest(
        targetOwnerId: String,
        targetOwnerGeneration: Long,
        onRequestFailure: (LiveFollowResult.Failure) -> Unit,
        request: () -> Unit,
    ) {
        try {
            request()
        } catch (error: Throwable) {
            if (!accepts(targetOwnerId, targetOwnerGeneration)) return
            onRequestFailure(LiveFollowResult.Failure("", error))
        }
    }

    private fun deliverFailure(
        operation: String,
        targetOwnerId: String,
        result: LiveFollowResult.Failure,
    ) {
        publish()
        logFailure(operation, targetOwnerId, result)
        onFailure(result.message)
    }

    private fun logFailure(
        operation: String,
        targetOwnerId: String,
        result: LiveFollowResult.Failure,
    ) {
        AtomicAudienceRuntimeRegistry.warning(
            "$operation 失败，ownerId=$targetOwnerId, message=${result.message}",
            result.error,
        )
    }

    private fun accepts(targetOwnerId: String, targetOwnerGeneration: Long): Boolean =
        !released && targetOwnerId == ownerId && targetOwnerGeneration == ownerGeneration

    private fun publish() {
        onStateChanged(visible, followed, requestRunning, fansCount)
    }
}
