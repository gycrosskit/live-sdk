import AtomicXCore
import ImSDK_Plus
final class Callback: GycLiveOperationCallback {
    var successes = 0; var failures = 0
    func onSuccess() { successes += 1 }
    func onFailure(code: Int32, message: String) { failures += 1 }
}
let sdk = LoginStore.shared
let im = V2TIMManager.instance
func runtime(_ user: String?, facadeReady: Bool = true) {
    im.actualUser = user; im.actualStatus = user == nil ? .loggedOut : .loggedIn
    sdk.state.value.loginStatus = facadeReady && user != nil ? .logined : .loggedOut
    sdk.state.value.loginUserInfo = user.map { UserProfile(userID: $0, nickname: $0, avatarURL: "") }
}
func login(_ client: GycLiveClient, _ user: String = "member") -> Callback {
    let callback = Callback()
    client.login(sdkAppId: 100, userId: user, userSig: "mock-signature", nickname: "", avatarUrl: "", callback: callback)
    return callback
}
func completeLogin(_ result: Result<Void, SdkError>) { sdk.logins.removeFirst()(result) }
func completeLogout(_ result: Result<Void, SdkError>) { sdk.logouts.removeFirst()(result) }
// 与 Android 的 isNotBlank 一致；拒绝空白凭据，但不改写服务端凭据原文。
runtime(nil)
let invalidCredentials = GycLiveClient()
for (user, signature) in [(" \n\t", "mock-signature"), ("member", " \n\t")] {
    let callback = Callback()
    invalidCredentials.login(sdkAppId: 100, userId: user, userSig: signature,
                             nickname: "", avatarUrl: "", callback: callback)
    assert(callback.failures == 1 && callback.successes == 0)
    assert(sdk.logins.isEmpty && !invalidCredentials.ownsTencentRuntime)
}
// LOGINED completion 先于资料；资料为空/旧值/获取失败都不能拒绝认证或失去清理权限。
for profile in [nil, "stale-profile"] as [String?] {
    runtime(nil)
    let client = GycLiveClient(); let callback = login(client)
    runtime("member")
    sdk.state.value.loginUserInfo = profile.map { UserProfile(userID: $0, nickname: $0, avatarURL: "") }
    completeLogin(.success(()))
    assert(callback.successes == 1 && callback.failures == 0 && client.ownsTencentRuntime)
    assert(client.isLoggedInAs(userId: "member"))
    let reused = login(client); assert(reused.successes == 1 && sdk.logins.isEmpty)
    let cleanup = Callback(); client.logout(callback: cleanup)
    assert(sdk.logouts.count == 1)
    runtime(nil); completeLogout(.success(())); assert(cleanup.successes == 1)
}
// A退出 → B退出 → A首次登录，不需要重试且每次真实注销。
let cycling = GycLiveClient()
for user in ["member-a", "member-b", "member-a"] {
    runtime(nil); let callback = login(cycling, user)
    runtime(user); sdk.state.value.loginUserInfo = nil; completeLogin(.success(()))
    assert(callback.successes == 1 && cycling.ownsTencentRuntime)
    let cleanup = Callback(); cycling.logout(callback: cleanup); assert(sdk.logouts.count == 1)
    runtime(nil); completeLogout(.success(())); assert(cleanup.successes == 1)
}
// 空 runtime 的真实成功才取得注销权限。
runtime(nil)
let owned = GycLiveClient(); let first = login(owned)
runtime("member"); completeLogin(.success(()))
assert(first.successes == 1 && owned.ownsTencentRuntime)
// facade 失效时需要准备；失败及错误成功回调都保留仍实际匹配的旧清理权限。
runtime("member", facadeReady: false)
let failed = login(owned); completeLogin(.failure(SdkError()))
assert(failed.failures == 1 && owned.ownsTencentRuntime)
let invalidSuccess = login(owned); completeLogin(.success(()))
assert(invalidSuccess.failures == 1 && owned.ownsTencentRuntime)
// 已有同用户 runtime 借用，logout 不调用厂商。
let borrowed = GycLiveClient(); let borrowedLogin = login(borrowed)
runtime("member"); completeLogin(.success(()))
assert(borrowedLogin.successes == 1 && !borrowed.ownsTencentRuntime)
let noLogout = Callback(); borrowed.logout(callback: noLogout)
assert(noLogout.successes == 1 && sdk.logouts.isEmpty)
// pending login 期间 reset/logout，使旧成功回调失效。
runtime(nil)
let late = GycLiveClient(); let lateLogin = login(late)
let reset = Callback(); late.logout(callback: reset)
runtime("member"); completeLogin(.success(()))
assert(lateLogin.failures == 1 && !late.ownsTencentRuntime && late.preparedLiveIdentity == nil)
// foreign owner 接管后不恢复旧权限，也不注销外部账号。
runtime("member", facadeReady: false)
let foreignFailure = login(owned)
runtime("foreign"); completeLogin(.failure(SdkError()))
assert(foreignFailure.failures == 1 && !owned.ownsTencentRuntime)
let foreignReset = Callback(); owned.logout(callback: foreignReset)
assert(foreignReset.successes == 1 && sdk.logouts.isEmpty)
// reset 换账号时，在旧logout回调期间外部接管，必须重新检查并拒绝下一次login。
runtime(nil)
let switching = GycLiveClient(); _ = login(switching)
runtime("member"); completeLogin(.success(()))
let next = login(switching, "next")
assert(sdk.logouts.count == 1)
runtime("foreign-during-reset"); completeLogout(.success(()))
assert(next.failures == 1 && sdk.logins.isEmpty && !switching.ownsTencentRuntime)
// 旧logout completion 不能清除新一代状态或报成功。
runtime(nil)
let stale = GycLiveClient(); _ = login(stale)
runtime("member"); completeLogin(.success(()))
let oldLogout = Callback(); stale.logout(callback: oldLogout)
let newLogout = Callback(); stale.logout(callback: newLogout)
completeLogout(.success(())); assert(oldLogout.failures == 1 && stale.ownsTencentRuntime)
completeLogout(.failure(SdkError())); assert(newLogout.failures == 1 && stale.ownsTencentRuntime)
print("Live production account callback contracts passed: own/borrow, pending reset, late logout, foreign and failed/invalid success ownership")

// 同user但已知AppId已被外部改变：准备不能RESET，注销及失败恢复都无旧权限。
runtime(nil); sdk.sdkAppID = 100
let appChanged = GycLiveClient(); _ = login(appChanged)
runtime("member"); completeLogin(.success(())); sdk.sdkAppID = 101
let rejectedApp = login(appChanged); assert(rejectedApp.failures == 1 && sdk.logins.isEmpty)
let releaseApp = Callback(); appChanged.logout(callback: releaseApp)
assert(releaseApp.successes == 1 && sdk.logouts.isEmpty && !appChanged.ownsTencentRuntime)
sdk.sdkAppID = 100; runtime(nil)
let changedDuringInit = GycLiveClient(); _ = login(changedDuringInit)
runtime("member"); completeLogin(.success(()))
runtime("member", facadeReady: false); let appFailure = login(changedDuringInit)
sdk.sdkAppID = 101; completeLogin(.failure(SdkError()))
assert(appFailure.failures == 1 && !changedDuringInit.ownsTencentRuntime)
sdk.sdkAppID = 100
print("Live same-user foreign SDKAppID preparation/cleanup/failure guards passed")

// facade 或 IM 任一没有真正登录，即使资料正确也不能算成功。
for invalidIM in [false, true] {
    runtime(nil); let client = GycLiveClient(); let callback = login(client)
    runtime("member", facadeReady: invalidIM)
    if invalidIM { im.actualStatus = .loggedOut }
    assert(!client.isLoggedInAs(userId: "member"))
    completeLogin(.success(())); assert(callback.failures == 1 && callback.successes == 0)
    let cleanup = Callback(); client.logout(callback: cleanup); assert(sdk.logouts.isEmpty)
}
// 同 user 但 callback 前已换 AppId，拒绝错误成功，不取得外部注销权限。
runtime(nil); sdk.sdkAppID = 100
let changedBeforeSuccess = GycLiveClient(); let appSuccess = login(changedBeforeSuccess)
runtime("member"); sdk.sdkAppID = 101; completeLogin(.success(()))
assert(appSuccess.failures == 1 && !changedBeforeSuccess.ownsTencentRuntime)
let appCleanup = Callback(); changedBeforeSuccess.logout(callback: appCleanup); assert(sdk.logouts.isEmpty)
sdk.sdkAppID = 100
print("Live profile-independent login, A/B/A logout, reuse and dual-status contracts passed")
