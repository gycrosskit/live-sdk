package io.trtc.tuikit.atomicxcore.api.login
import android.content.Context
import io.trtc.tuikit.atomicxcore.api.CompletionHandler
class Value<T>(var value: T)
enum class LoginStatus { LOGINED, LOGGED_OUT }
class UserProfile { var userID = ""; var nickname = ""; var avatarURL = "" }
class LoginState {
    val loginStatus = Value(LoginStatus.LOGGED_OUT)
    val loginUserInfo = Value<UserProfile?>(null)
}
class LoginStore private constructor() {
    val loginState = LoginState()
    var sdkAppID = 100
    val logins = ArrayDeque<CompletionHandler>()
    val logouts = ArrayDeque<CompletionHandler>()
    val profiles = mutableListOf<UserProfile>()
    fun login(context: Context, sdkAppId: Int, userId: String, userSig: String, callback: CompletionHandler) { logins.addLast(callback) }
    fun logout(callback: CompletionHandler) { logouts.addLast(callback) }
    // setSelfInfo 不填充 loginUserInfo：资料获取/写入可以迟到或失败。
    fun setSelfInfo(profile: UserProfile, callback: CompletionHandler?) { profiles += profile }
    companion object { val shared = LoginStore() }
}
