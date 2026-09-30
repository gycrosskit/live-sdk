package io.github.gycrosskit.livesdk

import com.tencent.imsdk.v2.V2TIMFollowInfo
import com.tencent.imsdk.v2.V2TIMFollowOperationResult
import com.tencent.imsdk.v2.V2TIMFollowTypeCheckResult
import com.tencent.imsdk.v2.V2TIMManager
import com.tencent.imsdk.v2.V2TIMValueCallback
import io.trtc.tuikit.atomicxcore.api.login.LoginStore

/** 把 V2TIM 批量回调收敛为单主播结果，并保证所有状态回调回到 Android 主线程。 */
internal object AndroidLiveHostFollowGateway : LiveHostFollowGateway {
    override val currentUserId: String
        get() = LoginStore.shared.loginState.loginUserInfo.value?.userID.orEmpty()

    override fun checkFollowed(
        userId: String,
        callback: (LiveFollowResult<Boolean>) -> Unit,
    ) {
        V2TIMManager.getFriendshipManager().checkFollowType(
            listOf(userId),
            object : V2TIMValueCallback<List<V2TIMFollowTypeCheckResult>> {
                override fun onSuccess(result: List<V2TIMFollowTypeCheckResult>?) {
                    val item = result.findUserResult(userId) { it.userID }
                    dispatchResult(item?.resultCode, item?.resultInfo, callback) {
                        item?.followType ==
                            V2TIMFollowTypeCheckResult.V2TIM_FOLLOW_TYPE_IN_MY_FOLLOWING_LIST ||
                            item?.followType ==
                            V2TIMFollowTypeCheckResult.V2TIM_FOLLOW_TYPE_IN_BOTH_FOLLOWERS_LIST
                    }
                }

                override fun onError(code: Int, desc: String?) =
                    dispatchFailure(code, desc, callback)
            },
        )
    }

    override fun updateFollow(
        userId: String,
        followed: Boolean,
        callback: (LiveFollowResult<Unit>) -> Unit,
    ) {
        val sdkCallback = object : V2TIMValueCallback<List<V2TIMFollowOperationResult>> {
            override fun onSuccess(result: List<V2TIMFollowOperationResult>?) {
                val item = result.findUserResult(userId) { it.userID }
                dispatchResult(item?.resultCode, item?.resultInfo, callback) { Unit }
            }

            override fun onError(code: Int, desc: String?) =
                dispatchFailure(code, desc, callback)
        }
        if (followed) {
            V2TIMManager.getFriendshipManager().followUser(listOf(userId), sdkCallback)
        } else {
            V2TIMManager.getFriendshipManager().unfollowUser(listOf(userId), sdkCallback)
        }
    }

    override fun fetchFans(
        userId: String,
        callback: (LiveFollowResult<Long>) -> Unit,
    ) {
        V2TIMManager.getFriendshipManager().getUserFollowInfo(
            listOf(userId),
            object : V2TIMValueCallback<List<V2TIMFollowInfo>> {
                override fun onSuccess(result: List<V2TIMFollowInfo>?) {
                    val item = result.findUserResult(userId) { it.userID }
                    dispatchResult(item?.resultCode, item?.resultInfo, callback) {
                        item?.followersCount ?: 0L
                    }
                }

                override fun onError(code: Int, desc: String?) =
                    dispatchFailure(code, desc, callback)
            },
        )
    }

    private fun <T> dispatchResult(
        resultCode: Int?,
        description: String?,
        callback: (LiveFollowResult<T>) -> Unit,
        value: () -> T,
    ) = AtomicMainThread.run {
        if (resultCode == 0) {
            callback(LiveFollowResult.Success(value()))
        } else {
            callback(LiveFollowResult.Failure(description.orEmpty()))
        }
    }

    private fun <T> dispatchFailure(
        code: Int,
        description: String?,
        callback: (LiveFollowResult<T>) -> Unit,
    ) = AtomicMainThread.run {
        callback(LiveFollowResult.Failure("code=$code, ${description.orEmpty()}"))
    }
}

/**
 * V2TIM 单用户请求有时返回无序单条结果；多条时只接受 userId 精确匹配，避免误用其他用户数据。
 */
private inline fun <T> List<T>?.findUserResult(
    targetUserId: String,
    userId: (T) -> String,
): T? {
    val values = orEmpty()
    return values.firstOrNull { userId(it) == targetUserId } ?: values.singleOrNull()
}
