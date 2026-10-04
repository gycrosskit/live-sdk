import GycLiveNative
import UIKit

/// 纯 UIKit 消费工程：不链接 Shared/KMP/Kuikly，所有公开签名均由实际 Pod 模块消费。
final class NativeCallbacks: GycLiveOperationCallback, GycLiveBooleanCallback, GycLiveLongCallback,
    GycLivePreviewObserver, GycLiveImObserver, GycAudiencePlayerObserver {
    func onSuccess() {}
    func onSuccess(value: Bool) {}
    func onSuccess(value: Int64) {}
    func onFailure(code: Int32, message: String) {}
    func onLoading() {}
    func onPlaying() {}
    func onFailed(code: Int32, message: String) {}
    func onRestCustomData(groupId: String, payload: String) {}
    func onCurrentUserRemoved(groupId: String, operatorUserId: String) {}
    func onGroupDismissed(groupId: String) {}
    func onKickedOffline() {}
    func onUserSigExpired() {}
    func onJoinSucceeded() {}
    func onLiveInfo(roomId: String, liveName: String, notice: String, ownerId: String,
                    ownerName: String, ownerAvatarUrl: String) {}
    func onJoinFailed(code: Int32, message: String) {}
    func onLiveEnded() {}
    func onKickedOut() {}
    func onInteractionReady() {}
    func onBarrageReceived(sequence: Int64, timestampSeconds: Double, senderId: String,
                           senderName: String, senderAvatarUrl: String, content: String) {}
    func onLikesReceived(count: Int32) {}
    func onAudienceChanged(userIds: [String], userNames: [String], userAvatarUrls: [String], count: Int32) {}
    func onMemberChanged(joined: Bool, userId: String, userName: String,
                         userAvatarUrl: String, timestampSeconds: Double) {}
    func onAudienceMessageDisabled(userId: String, disabled: Bool) {}
    func onPictureInPictureChanged(enabled: Bool) {}
}

@main
final class NativeConsumerApp: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = UIViewController()
        window.makeKeyAndVisible()
        self.window = window
        GycLiveClient.shared.setLogger { _, _ in }
        // 验证工程不自行创建账号或请求业务房间；人工提供凭据才能执行设备业务验收。
        if ProcessInfo.processInfo.arguments.contains("--exercise-live-api") { exerciseAPI() }
        return true
    }

    private func exerciseAPI() {
        let client = GycLiveClient.shared
        let callbacks = NativeCallbacks()
        _ = client.isLoggedInAs(userId: "consumer")
        _ = client.currentUserId()
        client.updateProfile(userId: "consumer", nickname: "consumer", avatarUrl: "")
        client.login(sdkAppId: 0, userId: "consumer", userSig: "", nickname: "consumer",
                     avatarUrl: "", callback: callbacks)
        let preview = client.makePreviewView(liveId: "consumer-room", observer: callbacks)
        client.releasePreviewView(view: preview)
        client.stopPreview()
        client.connectLiveIm(groupIds: ["consumer-room"], observer: callbacks)
        let audience = client.makeAudienceView(liveId: "consumer-room", observer: callbacks)
        client.sendBarrage(message: "consumer", callback: callbacks)
        client.sendLike(count: 1, callback: callbacks)
        client.checkFollowed(userId: "owner", callback: callbacks)
        client.updateFollow(userId: "owner", followed: true, callback: callbacks)
        client.fetchFans(userId: "owner", callback: callbacks)
        client.refreshAudience()
        _ = client.enterPictureInPicture(wideContent: false)
        _ = client.enableBackgroundPictureInPicture(wideContent: true) { _ in }
        client.disableBackgroundPictureInPicture()
        client.releaseAudienceView(view: audience, callback: callbacks)
        client.disconnectLiveIm()
        client.logout(callback: callbacks)
    }
}
