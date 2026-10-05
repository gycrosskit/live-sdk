import AtomicXCore
import Combine
import Foundation

/**
 * 持有单场直播的弹幕、点赞和观众列表 Store。
 *
 * `bind` 与 `reset` 定义完整会话边界；所有 Combine 回调都校验 liveID 与绑定代次，
 * 防止上一场直播的迟到事件污染新的 shared 页面状态。
 */
final class AtomicXAudienceInteractionSession {
    private struct BarrageIdentity: Hashable {
        let sequence: Int
        let timestampSeconds: Double
        let senderID: String
        let content: String
    }

    /// 同房重连仍撤销旧回调的绑定代次。
    private var generation = 0
    /// 当前互动会话绑定的直播 ID；nil 表示尚未进房或已经离房。
    private var activeLiveID: String?
    /// 把 SDK 互动事件转换给 live-sdk 的当前观察者。
    private var observer: GycAudiencePlayerObserver?
    /// 当前直播间的弹幕命令与状态 Store。
    private var barrageStore: BarrageStore?
    /// 当前直播间的点赞命令与事件 Store。
    private var likeStore: LikeStore?
    /// 当前直播间的观众列表 Store。
    private var audienceStore: LiveAudienceStore?
    /// 当前会话的全部 Combine 订阅；reset 时统一取消。
    private var cancellables = Set<AnyCancellable>()
    /// 最近一次观众列表快照。
    private var audienceUsers: [LiveUserInfo] = []
    /// SDK 报告的观众总数，可能大于分页列表长度。
    private var audienceCount: UInt = 0
    /// SDK 发布的是累计列表；只把本次新增项跨 K/N Bridge 发送给 shared。
    private var deliveredBarrageIdentities = Set<BarrageIdentity>()

    /// 进房成功后创建当前直播的互动 Store，并开始向观察者转发事件。
    @MainActor
    func bind(liveID: String, observer: GycAudiencePlayerObserver) {
        reset()
        let generation = self.generation
        activeLiveID = liveID
        self.observer = observer

        let barrageStore = BarrageStore.create(liveID: liveID)
        let likeStore = LikeStore.create(liveID: liveID)
        self.barrageStore = barrageStore
        self.likeStore = likeStore

        barrageStore.state.subscribe(
            StatePublisherSelector(keyPath: \BarrageState.messageList)
        )
        .receive(on: RunLoop.main)
        .sink { [weak self] messages in
            guard let self, self.activeLiveID == liveID, self.generation == generation else { return }
            let textMessages = messages.filter {
                $0.messageType == .text && !$0.textContent.isEmpty
            }
            let currentIdentities = Set(textMessages.map(Self.barrageIdentity))
            self.deliveredBarrageIdentities.formIntersection(currentIdentities)
            textMessages.forEach { barrage in
                guard self.deliveredBarrageIdentities.insert(Self.barrageIdentity(barrage)).inserted else {
                    return
                }
                self.observer?.onBarrageReceived(
                    sequence: Int64(barrage.sequence),
                    timestampSeconds: barrage.timestampInSecond,
                    senderId: barrage.sender.userID,
                    senderName: barrage.sender.userName,
                    senderAvatarUrl: barrage.sender.avatarURL,
                    content: barrage.textContent
                )
            }
        }
        .store(in: &cancellables)

        likeStore.likeEventPublisher
            .receive(on: RunLoop.main)
            .sink { [weak self] event in
                guard let self, self.activeLiveID == liveID, self.generation == generation else { return }
                if case .onReceiveLikesMessage(let eventLiveID, let count, _) = event,
                   eventLiveID == liveID {
                    self.observer?.onLikesReceived(count: Int32(clamping: count))
                }
            }
            .store(in: &cancellables)

        let audienceStore = LiveAudienceStore.create(liveID: liveID)
        self.audienceStore = audienceStore
        audienceStore.state.subscribe(
            StatePublisherSelector(keyPath: \AtomicXCore.LiveAudienceState.audienceList)
        )
        .receive(on: RunLoop.main)
        .sink { [weak self] users in
            guard let self, self.activeLiveID == liveID, self.generation == generation else { return }
            self.audienceUsers = users
            self.notifyAudienceChanged()
        }
        .store(in: &cancellables)
        audienceStore.state.subscribe(
            StatePublisherSelector(keyPath: \AtomicXCore.LiveAudienceState.audienceCount)
        )
        .receive(on: RunLoop.main)
        .sink { [weak self] count in
            guard let self, self.activeLiveID == liveID, self.generation == generation else { return }
            self.audienceCount = count
            self.notifyAudienceChanged()
        }
        .store(in: &cancellables)
        audienceStore.liveAudienceEventPublisher
            .receive(on: RunLoop.main)
            .sink { [weak self] event in
                guard let self, self.activeLiveID == liveID, self.generation == generation else { return }
                switch event {
                case .onOwnerJoined(let user),
                     .onAdminJoined(let user),
                     .onAudienceJoined(let user):
                    self.notifyMemberChanged(user: user, joined: true)
                case .onOwnerLeft(let user),
                     .onAdminLeft(let user),
                     .onAudienceLeft(let user):
                    self.notifyMemberChanged(user: user, joined: false)
                case .onAudienceMessageDisabled(let audience, let isDisable):
                    self.observer?.onAudienceMessageDisabled(
                        userId: audience.userID,
                        disabled: isDisable
                    )
                @unknown default:
                    break
                }
            }
            .store(in: &cancellables)
        audienceStore.fetchAudienceList(completion: nil)

        observer.onInteractionReady()
    }

    /// 通过当前 BarrageStore 发送原始文本；表情 Token 编码由 shared 完成。
    @MainActor
    func sendBarrage(message: String, callback: GycLiveOperationCallback) {
        guard let barrageStore else {
            callback.onFailure(code: -1, message: "直播互动尚未准备完成")
            return
        }
        barrageStore.sendTextMessage(text: message, extensionInfo: nil) { result in
            LiveMainThread.run {
                switch result {
                case .success:
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

    /// 通过当前 LikeStore 发送由 common 合并后的点赞计数。
    @MainActor
    func sendLike(count: Int32, callback: GycLiveOperationCallback) {
        guard count > 0, let likeStore else {
            callback.onFailure(code: -1, message: "直播互动尚未准备完成")
            return
        }
        likeStore.sendLike(count: UInt(count)) { result in
            LiveMainThread.run {
                switch result {
                case .success:
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

    /// 请求当前 AudienceStore 刷新分页观众列表；无活动会话时保持空操作。
    @MainActor
    func refreshAudience() {
        audienceStore?.fetchAudienceList(completion: nil)
    }

    /// 取消订阅并清空全部 Store、快照和观察者，形成不可接收旧回调的终态。
    @MainActor
    func reset() {
        generation += 1
        activeLiveID = nil
        cancellables.removeAll()
        barrageStore = nil
        likeStore = nil
        audienceStore = nil
        audienceUsers = []
        audienceCount = 0
        deliveredBarrageIdentities.removeAll()
        observer = nil
    }

    private static func barrageIdentity(_ barrage: Barrage) -> BarrageIdentity {
        BarrageIdentity(
            sequence: barrage.sequence,
            timestampSeconds: barrage.timestampInSecond,
            senderID: barrage.sender.userID,
            content: barrage.textContent
        )
    }

    /// 合并 SDK 人数与已加载列表长度，避免分页刷新造成展示人数短暂倒退。
    @MainActor
    private func notifyAudienceChanged() {
        observer?.onAudienceChanged(
            userIds: audienceUsers.map(\.userID),
            userNames: audienceUsers.map(\.userName),
            userAvatarUrls: audienceUsers.map(\.avatarURL),
            count: Int32(clamping: max(audienceCount, UInt(audienceUsers.count)))
        )
    }

    /// 成员事件只转换用户快照和时间；业务文案及弹幕语义由 shared 维护。
    @MainActor
    private func notifyMemberChanged(user: LiveUserInfo, joined: Bool) {
        observer?.onMemberChanged(
            joined: joined,
            userId: user.userID,
            userName: user.userName,
            userAvatarUrl: user.avatarURL,
            timestampSeconds: Date().timeIntervalSince1970
        )
    }
}
