import AtomicXCore
import ImSDK_Plus

/** AtomicX 全局账号接线；稳定门面仍由 `GycLiveClient` 对 KMP 暴露。 */
extension GycLiveClient {
    public func isLoggedInAs(userId: String) -> Bool {
        onMainSync {
            let state = LoginStore.shared.state.value
            return state.loginStatus == .logined && state.loginUserInfo?.userID == userId && self.actualTencentUser() == userId &&
                V2TIMManager.sharedInstance()?.getLoginStatus().rawValue == 1
        }
    }

    public func updateProfile(userId: String, nickname: String, avatarUrl: String) {
        onMain { [weak self] in
            guard let self, self.actualTencentUser() == userId else { return }
            self.setProfile(userId: userId, nickname: nickname, avatarUrl: avatarUrl)
        }
    }

    public func login(sdkAppId: Int32, userId: String, userSig: String, nickname: String,
                      avatarUrl: String, callback: GycLiveOperationCallback) {
        onMain { [weak self] in
            guard let self else { return }
            guard sdkAppId > 0, !userId.isEmpty, !userSig.isEmpty,
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
