package io.github.gycrosskit.livesdk

import android.content.Context
import com.tencent.imsdk.v2.V2TIMManager
import io.trtc.tuikit.atomicxcore.api.CompletionHandler
import io.trtc.tuikit.atomicxcore.api.login.LoginStatus
import io.trtc.tuikit.atomicxcore.api.login.LoginStore
import io.trtc.tuikit.atomicxcore.api.login.UserProfile

/** 隔离 AtomicX 登录类型，App 只传递服务端签发的账号凭证与业务资料。 */
object AtomicXSession {
    private var preparedIdentity: LiveSdkIdentity? = null
    private var ownsRuntime = false
    private var operationSerial = 0L
    /** 登录与注销只向上层暴露稳定结果，不泄漏 AtomicX CompletionHandler。 */
    interface Callback {
        fun onSuccess()

        fun onFailure(code: Int, message: String)
    }

    /** 当前 AtomicX 账号必须与业务账号完全一致，不能只依据 LOGINED 状态复用旧会话。 */
    fun isLoggedInAs(userId: String): Boolean {
        val state = LoginStore.shared.loginState
        return state.loginStatus.value == LoginStatus.LOGINED &&
            state.loginUserInfo.value?.userID == userId && actualUserId() == userId &&
            V2TIMManager.getInstance().loginStatus == V2TIMManager.V2TIM_STATUS_LOGINED
    }

    /** 更新直播侧资料；空昵称回退 userId，保证弹幕与在线成员始终有可展示名称。 */
    fun updateProfile(userId: String, nickname: String, avatarUrl: String) {
        if (actualUserId() == userId) updateProfile(LoginStore.shared, userId, nickname, avatarUrl)
    }

    internal fun currentUserId(): String =
        actualUserId().orEmpty()

    /** 使用服务端签发的 UserSig 登录；同账号已登录时只同步资料，不重复触发 SDK 登录。 */
    fun login(
        context: Context,
        sdkAppId: Int,
        userId: String,
        userSig: String,
        nickname: String,
        avatarUrl: String,
        callback: Callback,
    ) {
        AtomicMainThread.run {
            require(sdkAppId > 0 && userId.isNotBlank() && userSig.isNotBlank())
            if (V2TIMManager.getInstance().loginStatus == V2TIMManager.V2TIM_STATUS_LOGINING) {
                callback.onFailure(-1, "Tencent runtime login is pending")
                return@run
            }
            val store = LoginStore.shared
            val target = LiveSdkIdentity(sdkAppId, userId)
            val actual = actualUserId()
            val sdkReady = isLoggedInAs(userId)
            when (liveAccountPreparationAction(preparedIdentity, ownsRuntime, actual, target, sdkReady, store.sdkAppID)) {
                LiveAccountAction.REJECT -> { callback.onFailure(-1, "Refusing to replace a foreign Tencent identity"); return@run }
                LiveAccountAction.RESET -> {
                    logout(object : Callback {
                        override fun onSuccess() = login(context, sdkAppId, userId, userSig, nickname, avatarUrl, callback)
                        override fun onFailure(code: Int, message: String) = callback.onFailure(code, message)
                    })
                    return@run
                }
                LiveAccountAction.REUSE -> {
                    updateProfile(store, userId, nickname, avatarUrl); callback.onSuccess(); return@run
                }
                LiveAccountAction.INITIALIZE -> Unit
            }
            val serial = ++operationSerial
            val previousIdentity = preparedIdentity
            val mayOwn = actual == null || liveOwnsActualIdentity(preparedIdentity, ownsRuntime, actual, store.sdkAppID)
            val previouslyOwned = liveOwnsActualIdentity(preparedIdentity, ownsRuntime, actual, store.sdkAppID)
            preparedIdentity = null; ownsRuntime = false
            store.login(context, sdkAppId, userId, userSig, object : CompletionHandler {
                override fun onSuccess() = AtomicMainThread.run {
                    if (serial != operationSerial || actualUserId() != userId || !isLoggedInAs(userId) ||
                        (store.sdkAppID > 0 && store.sdkAppID != sdkAppId)) {
                        if (serial == operationSerial && liveOwnsActualIdentity(previousIdentity, previouslyOwned, actualUserId(), store.sdkAppID)) {
                            preparedIdentity = previousIdentity; ownsRuntime = true
                        }
                        callback.onFailure(-1, "Tencent identity changed while login was pending")
                        return@run
                    }
                    preparedIdentity = target; ownsRuntime = mayOwn
                    updateProfile(store, userId, nickname, avatarUrl)
                    callback.onSuccess()
                }
                override fun onFailure(code: Int, desc: String) = AtomicMainThread.run {
                    if (serial == operationSerial && liveOwnsActualIdentity(previousIdentity, previouslyOwned, actualUserId(), store.sdkAppID)) {
                        preparedIdentity = previousIdentity; ownsRuntime = true
                    }
                    callback.onFailure(code, desc)
                }
            })
        }
    }

    /** 借用/外部接管只清本组件状态；只有成功从空 runtime 登录的账号才可注销。 */
    fun logout(callback: Callback) = AtomicMainThread.run {
        val serial = ++operationSerial
        if (!ownsRuntime || V2TIMManager.getInstance().loginStatus == V2TIMManager.V2TIM_STATUS_LOGINING ||
            !liveOwnsActualIdentity(preparedIdentity, ownsRuntime, actualUserId(), LoginStore.shared.sdkAppID)) {
            preparedIdentity = null; ownsRuntime = false; callback.onSuccess(); return@run
        }
        val owned = preparedIdentity
        LoginStore.shared.logout(object : CompletionHandler {
            override fun onSuccess() = AtomicMainThread.run {
                if (serial != operationSerial) { callback.onFailure(-1, "Tencent cleanup superseded"); return@run }
                preparedIdentity = null; ownsRuntime = false
                callback.onSuccess()
            }
            override fun onFailure(code: Int, desc: String) = AtomicMainThread.run {
                if (serial != operationSerial) { callback.onFailure(-1, "Tencent cleanup superseded"); return@run }
                if (!liveOwnsActualIdentity(owned, true, actualUserId(), LoginStore.shared.sdkAppID)) {
                    preparedIdentity = null; ownsRuntime = false; callback.onSuccess()
                } else callback.onFailure(code, desc)
            }
        })
    }

    private fun actualUserId(): String? = V2TIMManager.getInstance().loginUser?.takeIf(String::isNotBlank)

    private fun updateProfile(
        store: LoginStore,
        userId: String,
        nickname: String,
        avatarUrl: String,
    ) {
        store.setSelfInfo(
            UserProfile().apply {
                userID = userId
                this.nickname = nickname.ifBlank { userId }
                this.avatarURL = avatarUrl
            },
            null,
        )
    }
}
