import AtomicXCore
import Foundation
import ImSDK_Plus

extension GycLiveClient {
    /// 每次绑定创建独占 Listener；旧 Listener 即使被 SDK 暂时保留，也不能访问新观察者。
    public func connectLiveIm(groupIds: [String], observer: GycLiveImObserver) {
        onMainSync { [self] in
            detachLiveImListeners()
            liveImGroupIDs = Set(groupIds.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) })
                .filter { !$0.isEmpty }
            liveImObserver = observer
            let listener = LiveImListener(client: self)
            liveImListener = listener
            let manager = V2TIMManager.sharedInstance()
            manager?.addGroupListener(listener: listener)
            manager?.addIMSDKListener(listener: listener)
        }
    }

    public func disconnectLiveIm() { onMainSync { [self] in detachLiveImListeners() } }

    public func currentUserId() -> String {
        onMainSync { self.actualTencentUser() ?? "" }
    }

    @MainActor
    func detachLiveImListeners() {
        if let listener = liveImListener {
            let manager = V2TIMManager.sharedInstance()
            manager?.removeGroupListener(listener: listener)
            manager?.removeIMSDKListener(listener: listener)
        }
        liveImListener = nil
        liveImGroupIDs.removeAll()
        liveImObserver = nil
    }
}

/// SDK 的无群组终态事件同样校验 Listener 身份，覆盖断开后重连同一房间的迟到回调。
final class LiveImListener: NSObject, V2TIMGroupListener, V2TIMSDKListener {
    private weak var client: GycLiveClient?
    init(client: GycLiveClient) { self.client = client }

    private func deliver(groupID: String? = nil, _ event: @escaping @MainActor (GycLiveImObserver) -> Void) {
        LiveMainThread.run { [weak self] in
            guard let self, let client = self.client,
                  client.liveImListener === self, let observer = client.liveImObserver else { return }
            if let groupID, !client.liveImGroupIDs.contains(groupID) { return }
            event(observer)
        }
    }

    func onReceiveRESTCustomData(groupID: String?, data: Data?) {
        guard let groupID, let data, let payload = String(data: data, encoding: .utf8) else { return }
        deliver(groupID: groupID) { $0.onRestCustomData(groupId: groupID, payload: payload) }
    }

    func onMemberKicked(groupID: String?, opUser: V2TIMGroupMemberInfo, memberList: [V2TIMGroupMemberInfo]) {
        guard let groupID else { return }
        deliver(groupID: groupID) { observer in
            let currentUserID = self.client?.actualTencentUser() ?? ""
            guard !currentUserID.isEmpty, memberList.contains(where: { $0.userID == currentUserID }) else { return }
            observer.onCurrentUserRemoved(groupId: groupID, operatorUserId: opUser.userID ?? "")
        }
    }

    func onGroupDismissed(groupID: String?, opUser: V2TIMGroupMemberInfo) {
        guard let groupID else { return }
        deliver(groupID: groupID) { $0.onGroupDismissed(groupId: groupID) }
    }

    private func terminal(_ event: @escaping @MainActor (GycLiveImObserver) -> Void) {
        LiveMainThread.run { [weak self] in
            guard let self, let client = self.client, client.liveImListener === self,
                  let observer = client.liveImObserver else { return }
            client.detachLiveImListeners()
            event(observer)
        }
    }
    func onKickedOffline() { terminal { $0.onKickedOffline() } }
    func onUserSigExpired() { terminal { $0.onUserSigExpired() } }
}
