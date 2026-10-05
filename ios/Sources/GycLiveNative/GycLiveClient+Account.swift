import AtomicXCore
import Foundation
import ImSDK_Plus

/** AtomicX 全局账号接线；稳定门面仍由 `GycLiveClient` 对 KMP 暴露。 */
extension GycLiveClient {
    /// 同步确认 AtomicX 与 IM 实际账号一致；后台查询等待 Main，不等同业务账号已准备。
    /// - Parameter userId: 腾讯账号 ID，按原值匹配。
    public func isLoggedInAs(userId: String) -> Bool {
        onMainSync {
            let state = LoginStore.shared.state.value
            return state.loginStatus == .logined && state.loginUserInfo?.userID == userId && self.actualTencentUser() == userId &&
                V2TIMManager.sharedInstance()?.getLoginStatus().rawValue == 1
        }
    }

    /// 在 Main 更新匹配账号资料；不匹配身份忽略，返回不代表后台派发已完成。
    /// - Parameter userId: 腾讯账号 ID，按原值匹配。
    /// - Parameter nickname: 展示昵称，空字符串回退 userId；空白文本按原值保留。
    /// - Parameter avatarUrl: 头像 URL，可为空。
    public func updateProfile(userId: String, nickname: String, avatarUrl: String) {
        onMain { [weak self] in
            guard let self, self.actualTencentUser() == userId else { return }
            self.setProfile(userId: userId, nickname: nickname, avatarUrl: avatarUrl)
        }
    }

    /// Main 登录服务端签发账号，拒绝替换外部身份；成功借用的同账号不取得注销权限。
    /// - Parameter sdkAppId: 腾讯 SDK 应用 ID，必须大于 0。
    /// - Parameter userId: 腾讯账号 ID，按原值匹配。
    /// - Parameter userSig: 服务端 UserSig，必须非空白，按原值交给 SDK。
    /// - Parameter nickname: 展示昵称，空字符串回退 userId；空白文本按原值保留。
    /// - Parameter avatarUrl: 头像 URL，可为空。
    /// - Parameter callback: Main 交付当前操作结果；借用身份清理成功不表示 SDK 已注销。
    public func login(sdkAppId: Int32, userId: String, userSig: String, nickname: String,
                      avatarUrl: String, callback: GycLiveOperationCallback) {
        onMain { [weak self] in
            guard let self else { return }
            guard sdkAppId > 0,
                  !userId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  !userSig.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  V2TIMManager.sharedInstance()?.getLoginStatus().rawValue != 2 else {
                callback.onFailure(code: -1, message: "Invalid or pending Tencent login")
                return
            }
            let target = LiveSdkIdentity(sdkAppId: sdkAppId, userId: userId)
            let actual = self.actualTencentUser()
            let sdkReady = self.isLoggedInAs(userId: userId)
            switch liveAccountPreparationAction(prepared: self.preparedLiveIdentity, ownsRuntime: self.ownsTencentRuntime,
                actualUser: actual, target: target, sdkReady: sdkReady, configuredSdkAppId: LoginStore.shared.sdkAppID) {
            case .reject:
                callback.onFailure(code: -1, message: "Refusing to replace a foreign Tencent identity")
                return
            case .reuse:
                self.setProfile(userId: userId, nickname: nickname, avatarUrl: avatarUrl)
                callback.onSuccess()
                return
            case .reset:
                self.logout(callback: LiveAccountCallback(success: {
                    self.login(sdkAppId: sdkAppId, userId: userId, userSig: userSig, nickname: nickname,
                               avatarUrl: avatarUrl, callback: callback)
                }, failure: { callback.onFailure(code: $0, message: $1) }))
                return
            case .initialize: break
            }
            self.accountOperationSerial += 1
            let serial = self.accountOperationSerial
            let previousIdentity = self.preparedLiveIdentity
            let previouslyOwned = liveOwnsActualIdentity(prepared: previousIdentity, ownsRuntime: self.ownsTencentRuntime, actualUser: actual, configuredSdkAppId: LoginStore.shared.sdkAppID)
            let mayOwn = actual == nil || liveOwnsActualIdentity(prepared: self.preparedLiveIdentity,
                ownsRuntime: self.ownsTencentRuntime, actualUser: actual, configuredSdkAppId: LoginStore.shared.sdkAppID)
            self.preparedLiveIdentity = nil; self.ownsTencentRuntime = false
            LoginStore.shared.login(sdkAppID: sdkAppId, userID: userId, userSig: userSig) { result in
                self.onMain {
                    switch result {
                    case .success:
                        guard serial == self.accountOperationSerial, self.isLoggedInAs(userId: userId),
                              LoginStore.shared.sdkAppID <= 0 || LoginStore.shared.sdkAppID == sdkAppId else {
                            if serial == self.accountOperationSerial, liveOwnsActualIdentity(prepared: previousIdentity, ownsRuntime: previouslyOwned, actualUser: self.actualTencentUser(), configuredSdkAppId: LoginStore.shared.sdkAppID) {
                                self.preparedLiveIdentity = previousIdentity; self.ownsTencentRuntime = true
                            }
                            callback.onFailure(code: -1, message: "Tencent identity changed while login was pending")
                            return
                        }
                        self.preparedLiveIdentity = target; self.ownsTencentRuntime = mayOwn
                        self.setProfile(userId: userId, nickname: nickname, avatarUrl: avatarUrl)
                        callback.onSuccess()
                    case .failure(let error):
                        if serial == self.accountOperationSerial, liveOwnsActualIdentity(prepared: previousIdentity, ownsRuntime: previouslyOwned, actualUser: self.actualTencentUser(), configuredSdkAppId: LoginStore.shared.sdkAppID) {
                            self.preparedLiveIdentity = previousIdentity; self.ownsTencentRuntime = true
                        }
                        callback.onFailure(code: Int32(clamping: error.code), message: error.message)
                    }
                }
            }
        }
    }

    /// 先关闭本组件的观看/监听；借用身份和 foreign runtime 不执行 SDK 注销。
    /// - Parameter callback: Main 交付当前操作结果；借用身份清理成功不表示 SDK 已注销。
    public func logout(callback: GycLiveOperationCallback) {
        onMain { [weak self] in
            guard let self else { return }
            self.previewSession.stop()
            self.releaseActiveAudience(leaveRoom: true)
            self.detachLiveImListeners()
            self.accountOperationSerial += 1
            let serial = self.accountOperationSerial
            guard self.ownsTencentRuntime,
                  V2TIMManager.sharedInstance()?.getLoginStatus().rawValue != 2,
                  liveOwnsActualIdentity(prepared: self.preparedLiveIdentity, ownsRuntime: self.ownsTencentRuntime,
                                        actualUser: self.actualTencentUser(), configuredSdkAppId: LoginStore.shared.sdkAppID) else {
                self.preparedLiveIdentity = nil; self.ownsTencentRuntime = false
                callback.onSuccess()
                return
            }
            let owned = self.preparedLiveIdentity
            LoginStore.shared.logout { result in
                self.onMain {
                    guard serial == self.accountOperationSerial else {
                        callback.onFailure(code: -1, message: "Tencent cleanup superseded")
                        return
                    }
                    switch result {
                    case .success:
                        self.preparedLiveIdentity = nil; self.ownsTencentRuntime = false
                        callback.onSuccess()
                    case .failure(let error):
                        if !liveOwnsActualIdentity(prepared: owned, ownsRuntime: true, actualUser: self.actualTencentUser(), configuredSdkAppId: LoginStore.shared.sdkAppID) {
                            self.preparedLiveIdentity = nil; self.ownsTencentRuntime = false
                            callback.onSuccess()
                        } else {
                            callback.onFailure(code: Int32(clamping: error.code), message: error.message)
                        }
                    }
                }
            }
        }
    }

    @MainActor
    func actualTencentUser() -> String? {
        guard let user = V2TIMManager.sharedInstance()?.getLoginUser(), !user.isEmpty else { return nil }
        return user
    }

    /// 将业务资料写入 AtomicX 全局 LoginStore；空昵称回退 userId，避免 SDK 保存空展示名。
    @MainActor
    private func setProfile(userId: String, nickname: String, avatarUrl: String) {
        let profile = UserProfile(
            userID: userId,
            nickname: nickname.isEmpty ? userId : nickname,
            avatarURL: avatarUrl
        )
        LoginStore.shared.setSelfInfo(userProfile: profile, completion: nil)
    }
}

private final class LiveAccountCallback: GycLiveOperationCallback {
    let success: () -> Void
    let failure: (Int32, String) -> Void
    init(success: @escaping () -> Void, failure: @escaping (Int32, String) -> Void) { self.success = success; self.failure = failure }
    func onSuccess() { success() }
    func onFailure(code: Int32, message: String) { failure(code, message) }
}
