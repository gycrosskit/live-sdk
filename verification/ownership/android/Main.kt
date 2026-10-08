package io.github.gycrosskit.livesdk
import android.content.Context
import com.tencent.imsdk.v2.V2TIMManager
import io.trtc.tuikit.atomicxcore.api.login.*
import java.io.File

private val sdk = LoginStore.shared
private val im = V2TIMManager.getInstance()
private class Callback : AtomicXSession.Callback {
    var successes = 0; var failures = 0
    override fun onSuccess() { successes++ }
    override fun onFailure(code: Int, message: String) { failures++ }
}
private fun runtime(user: String?, profile: String? = user, ready: Boolean = true) {
    im.loginUser = user; im.loginStatus = if (user == null) 3 else V2TIMManager.V2TIM_STATUS_LOGINED
    sdk.loginState.loginStatus.value = if (ready && user != null) LoginStatus.LOGINED else LoginStatus.LOGGED_OUT
    sdk.loginState.loginUserInfo.value = profile?.let { UserProfile().apply { userID = it } }
}
private fun login(user: String = "member-a") = Callback().also {
    AtomicXSession.login(Context(), 100, user, "fixture-signature", "", "", it)
}
private fun logout() = Callback().also(AtomicXSession::logout)
private fun success(user: String = "member-a", profile: String? = null) {
    runtime(user, profile); sdk.logins.removeFirst().onSuccess()
}
private fun own() { runtime(null); val cb = login(); success(); check(cb.successes == 1) }
private fun completeLogout() { runtime(null); sdk.logouts.removeFirst().onSuccess() }
private fun clean() {
    runtime(null); sdk.sdkAppID = 100; logout()
    sdk.logins.clear(); sdk.logouts.clear(); sdk.profiles.clear()
}
fun main(args: Array<String>) {
    val tests = linkedMapOf<String, () -> Unit>(
        "first-login-profile-empty" to {
            val cb = login(); success()
            check(cb.successes == 1 && cb.failures == 0) { "first login rejected before asynchronous profile" }
            check(AtomicXSession.isLoggedInAs("member-a"))
            check(sdk.profiles.single().userID == "member-a")
            logout(); check(sdk.logouts.size == 1) { "owned login lost SDK logout permission" }; completeLogout()
        },
        "first-login-stale-profile" to {
            val cb = login(); success(profile = "stale-profile")
            check(cb.successes == 1 && AtomicXSession.isLoggedInAs("member-a"))
            logout(); check(sdk.logouts.size == 1); completeLogout()
        },
        "a-logout-b-logout-a" to {
            for (user in listOf("member-a", "member-b", "member-a")) {
                val cb = login(user); success(user)
                check(cb.successes == 1); logout(); check(sdk.logouts.size == 1); completeLogout()
            }
        },
        "same-account-reuse-delayed-failed-profile" to {
            own(); sdk.loginState.loginUserInfo.value = null
            check(login().successes == 1 && sdk.logins.isEmpty())
            sdk.loginState.loginUserInfo.value = UserProfile().apply { userID = "stale-profile" }
            check(login().successes == 1 && sdk.logins.isEmpty())
            check(AtomicXSession.isLoggedInAs("member-a"))
        },
        "borrowed-does-not-logout" to {
            runtime("member-a", null); val cb = login(); success()
            check(cb.successes == 1); check(logout().successes == 1 && sdk.logouts.isEmpty())
        },
        "foreign-account-rejected" to {
            runtime("foreign", null); check(login().failures == 1 && sdk.logins.isEmpty())
            check(logout().successes == 1 && sdk.logouts.isEmpty())
        },
        "foreign-app-id-rejected" to {
            own(); sdk.sdkAppID = 101
            check(login().failures == 1 && sdk.logins.isEmpty())
            check(logout().successes == 1 && sdk.logouts.isEmpty())
        },
        "foreign-during-login-success" to {
            val cb = login(); runtime("foreign", "member-a"); sdk.logins.removeFirst().onSuccess()
            check(cb.failures == 1 && cb.successes == 0); logout(); check(sdk.logouts.isEmpty())
        },
        "app-id-changed-during-login-success" to {
            val cb = login(); sdk.sdkAppID = 101; success()
            check(cb.failures == 1 && cb.successes == 0); logout(); check(sdk.logouts.isEmpty())
        },
        "stale-login-callback" to {
            val old = login(); logout(); val current = login("member-b")
            success(); check(old.failures == 1 && old.successes == 0)
            success("member-b"); check(current.successes == 1)
            logout(); check(sdk.logouts.size == 1); completeLogout()
        },
        "stale-logout-callback" to {
            own(); val old = logout(); val current = logout()
            sdk.logouts.removeFirst().onSuccess(); check(old.failures == 1)
            sdk.logouts.removeFirst().onFailure(-9, "fixture"); check(current.failures == 1)
            logout(); check(sdk.logouts.size == 1); completeLogout()
        },
        "failed-reprepare-preserves-owned-cleanup" to {
            own(); runtime("member-a", null, ready = false); val cb = login()
            sdk.logins.removeFirst().onFailure(-9, "fixture"); check(cb.failures == 1)
            logout(); check(sdk.logouts.size == 1); completeLogout()
        },
        "foreign-during-reset-rejected" to {
            own(); val cb = login("member-b"); check(sdk.logouts.size == 1)
            runtime("foreign"); sdk.logouts.removeFirst().onSuccess()
            check(cb.failures == 1 && sdk.logins.isEmpty())
        },
        "foreign-during-login-failure" to {
            own(); runtime("member-a", null, ready = false); val cb = login()
            runtime("foreign"); sdk.logins.removeFirst().onFailure(-9, "fixture")
            check(cb.failures == 1); logout(); check(sdk.logouts.isEmpty())
        },
        "app-id-changed-during-login-failure" to {
            own(); runtime("member-a", null, ready = false); val cb = login()
            sdk.sdkAppID = 101; sdk.logins.removeFirst().onFailure(-9, "fixture")
            check(cb.failures == 1); logout(); check(sdk.logouts.isEmpty())
        },
        "invalid-im-success-rejected" to {
            val cb = login(); runtime("member-a", "member-a"); im.loginStatus = 3
            check(!AtomicXSession.isLoggedInAs("member-a"))
            sdk.logins.removeFirst().onSuccess(); check(cb.failures == 1)
            logout(); check(sdk.logouts.isEmpty())
        },
        "invalid-facade-success-rejected" to {
            val cb = login(); runtime("member-a", null, ready = false); sdk.logins.removeFirst().onSuccess()
            check(cb.failures == 1); logout(); check(sdk.logouts.isEmpty())
        },
        "pending-im-rejected" to {
            im.loginStatus = V2TIMManager.V2TIM_STATUS_LOGINING
            check(login().failures == 1 && sdk.logins.isEmpty())
        },
    )
    var failures = 0
    val cases = tests.map { (name, test) ->
        clean()
        try { test(); println("PASS $name"); "<testcase name=\"$name\"/>" }
        catch (error: Throwable) { failures++; println("FAIL $name: $error"); "<testcase name=\"$name\"><failure>${error.javaClass.simpleName}</failure></testcase>" }
    }
    File(args.single()).writeText("<testsuite tests=\"${tests.size}\" failures=\"$failures\" errors=\"0\" skipped=\"0\">${cases.joinToString("")}</testsuite>")
    check(failures == 0) { "$failures production account contracts failed" }
}
