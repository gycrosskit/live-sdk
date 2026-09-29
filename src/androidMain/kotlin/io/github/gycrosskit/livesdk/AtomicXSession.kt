package io.github.gycrosskit.livesdk

import android.content.Context
import io.trtc.tuikit.atomicxcore.api.CompletionHandler
import io.trtc.tuikit.atomicxcore.api.login.LoginStatus
import io.trtc.tuikit.atomicxcore.api.login.LoginStore
import io.trtc.tuikit.atomicxcore.api.login.UserProfile

/** 隔离 AtomicX 登录类型，App 只传递服务端签发的账号凭证与业务资料。 */
object AtomicXSession {
    /** 登录与注销只向上层暴露稳定结果，不泄漏 AtomicX CompletionHandler。 */
    interface Callback {
        fun onSuccess()

        fun onFailure(code: Int, message: String)
    }

    /** 当前 AtomicX 账号必须与业务账号完全一致，不能只依据 LOGINED 状态复用旧会话。 */
    fun isLoggedInAs(userId: String): Boolean {
        val state = LoginStore.shared.loginState
        return state.loginStatus.value == LoginStatus.LOGINED &&
            state.loginUserInfo.value?.userID == userId
    }

    /** 更新直播侧资料；空昵称回退 userId，保证弹幕与在线成员始终有可展示名称。 */
    fun updateProfile(userId: String, nickname: String, avatarUrl: String) {
        updateProfile(LoginStore.shared, userId, nickname, avatarUrl)
    }

    internal fun currentUserId(): String =
        LoginStore.shared.loginState.loginUserInfo.value?.userID.orEmpty()

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
        val store = LoginStore.shared
        val state = store.loginState
        val currentUserId = state.loginUserInfo.value?.userID
        if (state.loginStatus.value == LoginStatus.LOGINED && currentUserId == userId) {
            updateProfile(store, userId, nickname, avatarUrl)
            callback.onSuccess()
            return
        }

        store.login(
            context,
            sdkAppId,
            userId,
            userSig,
            object : CompletionHandler {
                override fun onSuccess() {
                    updateProfile(store, userId, nickname, avatarUrl)
                    callback.onSuccess()
                }

                override fun onFailure(code: Int, desc: String) {
                    callback.onFailure(code, desc)
                }
            },
        )
    }

    /** 注销当前 AtomicX 账号；宿主在业务账号退出或切换时调用。 */
    fun logout(callback: Callback) {
        if (LoginStore.shared.loginState.loginStatus.value == LoginStatus.UNLOGIN) {
            callback.onSuccess()
            return
        }
        LoginStore.shared.logout(
            object : CompletionHandler {
                override fun onSuccess() {
                    callback.onSuccess()
                }

                override fun onFailure(code: Int, desc: String) {
                    // 回调失败但 SDK 已登出时清理已完成；仍登录则必须阻止新账号 SDK 准备。
                    if (LoginStore.shared.loginState.loginStatus.value == LoginStatus.UNLOGIN) {
                        callback.onSuccess()
                    } else {
                        callback.onFailure(code, desc)
                    }
                }
            },
        )
    }

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
