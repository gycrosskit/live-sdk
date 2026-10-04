import AtomicXCore

/** AtomicX 全局账号接线；稳定门面仍由 `GycLiveClient` 对 KMP 暴露。 */
extension GycLiveClient {
    /// 同步查询 AtomicX 全局账号是否已登录为指定业务用户。
    public func isLoggedInAs(userId: String) -> Bool {
        onMainSync {
            let state = LoginStore.shared.state.value
            return state.loginStatus == .logined && state.loginUserInfo?.userID == userId
        }
    }

    /// 更新当前 AtomicX/IM 用户资料；UserSig 和业务用户刷新仍由 shared 负责。
    public func updateProfile(userId: String, nickname: String, avatarUrl: String) {
        onMain { [weak self] in
            self?.setProfile(userId: userId, nickname: nickname, avatarUrl: avatarUrl)
        }
    }

    /** 登录 AtomicX 全局账号，并在成功后同步普通直播昵称和头像。 */
    public func login(
        sdkAppId: Int32,
        userId: String,
        userSig: String,
        nickname: String,
        avatarUrl: String,
        callback: GycLiveOperationCallback
    ) {
        onMain { [weak self] in
            guard let self else { return }
            let state = LoginStore.shared.state.value
            if state.loginStatus == .logined, state.loginUserInfo?.userID == userId {
                setProfile(userId: userId, nickname: nickname, avatarUrl: avatarUrl)
                callback.onSuccess()
                return
            }

            let performLogin = { [weak self] in
                guard let self else { return }
                LoginStore.shared.login(
                    sdkAppID: sdkAppId,
                    userID: userId,
                    userSig: userSig
                ) { result in
                    self.onMain {
                        switch result {
                        case .success:
                            self.setProfile(
                                userId: userId,
                                nickname: nickname,
                                avatarUrl: avatarUrl
                            )
                            callback.onSuccess()
                        case .failure(let error):
                            callback.onFailure(
                                code: Int32(clamping: error.code),
                                message: error.message
                            )
                        }
                    }
                }
            }

            // AtomicX 共享全局账号；切换业务用户前必须先释放旧账号，避免复用错误的 IM 会话。
            if state.loginStatus == .logined {
                LoginStore.shared.logout { [weak self] result in
                    self?.onMain {
                        switch result {
                        case .success:
                            performLogin()
                        case .failure(let error):
                            if LoginStore.shared.state.value.loginStatus == .unlogin {
                                performLogin()
                            } else {
                                callback.onFailure(
                                    code: Int32(clamping: error.code),
                                    message: error.message
                                )
                            }
                        }
                    }
                }
            } else {
                performLogin()
            }
        }
    }

    /// 停止预览、离开直播、移除 IM 监听后注销 AtomicX 全局账号。
    public func logout(callback: GycLiveOperationCallback) {
        onMain { [weak self] in
            guard let self else { return }
            previewSession.stop()
            releaseActiveAudience(leaveRoom: true)
            detachLiveImListeners()
            guard LoginStore.shared.state.value.loginStatus == .logined else {
                callback.onSuccess()
                return
            }
            LoginStore.shared.logout { result in
                self.onMain {
                    switch result {
                    case .success:
                        callback.onSuccess()
                    case .failure(let error):
                        // SDK 报未初始化时只以实际登出状态判定幂等完成；仍登录则保留清理屏障。
                        if LoginStore.shared.state.value.loginStatus == .unlogin {
                            callback.onSuccess()
                        } else {
                            callback.onFailure(
                                code: Int32(clamping: error.code),
                                message: error.message
                            )
                        }
                    }
                }
            }
        }
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
