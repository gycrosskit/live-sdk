import AtomicXCore
import Combine
import ImSDK_Plus
import UIKit

/**
 * 腾讯 AtomicXCore 的 iOS 原生适配器。
 *
 * 本类是 UIKit / Swift 可见的稳定门面，负责账号、完整观看会话、IM 和 PiP；列表预览与观众互动分别委托给
 * 独立会话对象。业务 UserSig 获取、页面状态、提示及直播扩展仍由宿主负责。
 * 所有 SDK 调用统一切到 MainActor，避免 Kotlin 协程线程直接访问 UIKit/AtomicX 状态。
 */
public final class GycLiveClient: NSObject {
    /// AtomicX Store 与 IM 账号是进程级对象，宿主共用同一个原生实例。
    public static let shared = GycLiveClient()

    private override init() {
        super.init()
    }

    // MARK: - 子会话

    /// 管理列表中唯一的静音预览，不参与完整直播间的 join/leave 生命周期。
    let previewSession = AtomicXPreviewSession()
    /// 管理单场直播的弹幕、点赞与观众 Store，离房时统一 reset。
    private let interactionSession = AtomicXAudienceInteractionSession()

    // MARK: - 完整直播间状态

    /// 当前唯一完整直播播放 View；实际类型由 AtomicX 创建，对消费方仅暴露 UIView。
    weak var activeAudienceView: UIView?
    /// 当前完整观看会话的腾讯直播 ID；回调另外校验 View 身份或会话代次。
    var activeLiveID: String?
    /// 把 AtomicX 播放、互动和 PiP 事件转换给 live-sdk 的观察者。
    var audienceObserver: GycAudiencePlayerObserver?
    /// 关播和被踢事件订阅，离房时必须主动取消。
    private var liveEventCancellable: AnyCancellable?

    // MARK: - 业务 IM 与系统 PiP

    /// 当前路由允许接收的 IM 群组 ID，由宿主传入已校验的群组。
    var liveImGroupIDs = Set<String>()
    /// 把 V2TIM 原始群事件转交 shared 协议解析器的观察者。
    var liveImObserver: GycLiveImObserver?
    /// 当前独占的 V2TIM Listener，同时注册群事件与账号事件。
    var liveImListener: LiveImListener?
    /// RoomEngine 当前是否可能持有系统 PiP；请求发出后即先置位，
    /// 便于退出时补发关闭命令。
    var pictureInPictureEnabled = false
    /// PiP 请求代次；只接受最后一次异步实验接口响应。
    var pictureInPictureRequestSerial = 0

    /// 同房重连也必须撤销旧 Store 订阅，不能只以 liveID 判定归属。
    var audienceGeneration = 0
    private var logger: ((GycLiveLogLevel, String) -> Void)?

    public func setLogger(_ logger: @escaping (GycLiveLogLevel, String) -> Void) {
        onMain { [weak self] in self?.logger = logger }
    }

    @MainActor
    func log(_ level: GycLiveLogLevel, _ message: String) { logger?(level, message) }

    // MARK: - 列表静音预览

    /// 创建当前唯一静音预览 View；新预览会先释放旧预览，但不会调用 joinLive。
    public func makePreviewView(liveId: String, observer: GycLivePreviewObserver) -> UIView {
        onMainSync {
            self.previewSession.makeView(liveID: liveId, observer: observer)
        }
    }

    /// 只在传入 View 仍是当前预览时释放，避免迟到的 Compose dispose 停掉新卡片。
    public func releasePreviewView(view: UIView) {
        onMain { [weak self, weak view] in
            guard let self, let view else { return }
            self.previewSession.release(view: view)
        }
    }

    /// 进完整直播间、页面失活或根会话退出时幂等停止当前预览。
    public func stopPreview() {
        onMain { [weak self] in
            self?.previewSession.stop()
        }
    }

    // MARK: - 直播互动命令

    /// 通过当前 BarrageStore 发送原始文本；表情 Token 编码由 shared 完成。
    public func sendBarrage(message: String, callback: GycLiveOperationCallback) {
        onMain { [weak self] in
            self?.interactionSession.sendBarrage(message: message, callback: callback)
        }
    }

    /// 通过当前 LikeStore 发送已由 common 合并后的点赞计数。
    public func sendLike(count: Int32, callback: GycLiveOperationCallback) {
        onMain { [weak self] in
            self?.interactionSession.sendLike(count: count, callback: callback)
        }
    }

    // MARK: - 完整直播 View

    /// 创建唯一完整直播 View 并加入观看会话；进房成功后才绑定互动 Store。
    public func makeAudienceView(liveId: String, observer: GycAudiencePlayerObserver) -> UIView {
        onMainSync {
            self.previewSession.stop()
            self.releaseActiveAudience(leaveRoom: true)

            let coreView = LiveCoreView(viewType: .playView)
            coreView.backgroundColor = .black
            // 直播间清屏、连击点赞等手势统一由 shared CMP 处理，原生层不得抢占触摸。
            coreView.isUserInteractionEnabled = false
            coreView.clipsToBounds = true
            self.activeAudienceView = coreView
            self.activeLiveID = liveId
            self.audienceObserver = observer
            coreView.setLiveID(liveId)
            self.observeLiveEvents(liveID: liveId)

            LiveListStore.shared.joinLive(liveID: liveId) { [weak self, weak coreView] result in
                self?.onMain {
                    guard let self,
                          let coreView,
                          self.activeAudienceView === coreView,
                          self.activeLiveID == liveId else { return }
                    switch result {
                    case .success(let liveInfo):
                        self.interactionSession.bind(liveID: liveId, observer: observer)
                        let owner = liveInfo.liveOwner
                        self.audienceObserver?.onLiveInfo(
                            roomId: liveInfo.liveID,
                            liveName: liveInfo.liveName,
                            notice: liveInfo.notice,
                            ownerId: owner.userID,
                            ownerName: owner.userName,
                            ownerAvatarUrl: owner.avatarURL
                        )
                        self.audienceObserver?.onJoinSucceeded()
                    case .failure(let error):
                        self.audienceObserver?.onJoinFailed(
                            code: Int32(clamping: error.code),
                            message: error.message
                        )
                    }
                }
            }
            return coreView
        }
    }

    /// 只释放仍为当前实例的播放 View，并在真实 leaveLive 完成后回调。
    public func releaseAudienceView(view: UIView, callback: GycLiveOperationCallback) {
        onMain { [weak self, weak view] in
            guard let self, let view, self.activeAudienceView === view else {
                callback.onSuccess()
                return
            }
            self.releaseActiveAudience(leaveRoom: true) { code, message in
                if let code {
                    callback.onFailure(code: Int32(clamping: code), message: message ?? "")
                } else {
                    callback.onSuccess()
                }
            }
        }
    }

    /// 只订阅当前 liveID 的关播/被踢事件，切房后旧事件不能污染新会话。
    @MainActor
    private func observeLiveEvents(liveID: String) {
        let generation = audienceGeneration
        liveEventCancellable = LiveListStore.shared.liveListEventPublisher
            .receive(on: RunLoop.main)
            .sink { [weak self] event in
                guard let self, activeLiveID == liveID,
                      audienceGeneration == generation else { return }
                switch event {
                case .onLiveEnded(let eventLiveID, _, _):
                    guard eventLiveID == liveID else { return }
                    audienceObserver?.onLiveEnded()
                case .onKickedOutOfLive(let eventLiveID, _, let message):
                    guard eventLiveID == liveID else { return }
                    log(.warning, "当前观众被移出直播间，liveID=\(liveID), reasonLength=\(message.count)")
                    audienceObserver?.onKickedOut()
                @unknown default:
                    break
                }
            }
    }

    /// 请求当前 AudienceStore 刷新观众列表；无活动会话时保持空操作。
    public func refreshAudience() {
        onMain { [weak self] in
            self?.interactionSession.refreshAudience()
        }
    }

    /**
     * 查询主播关注状态。
     *
     * 关注状态机位于 live-sdk/commonMain；组件只转换 V2TIM 单用户结果，
     * 并统一回主线程修改 Compose 状态。
     */
    public func checkFollowed(userId: String, callback: GycLiveBooleanCallback) {
        onMain { [weak self] in
            V2TIMManager.sharedInstance().checkFollowType(userIDList: [userId]) { [weak self] results in
                self?.onMain {
                    guard let item = results?.first, item.resultCode == 0 else {
                        let item = results?.first
                        callback.onFailure(
                            code: Int32(clamping: item?.resultCode ?? -1),
                            message: item?.resultInfo ?? "检查关注状态失败"
                        )
                        return
                    }
                    let followed = item.followType == .FOLLOW_TYPE_IN_BOTH_FOLLOWERS_LIST ||
                        item.followType == .FOLLOW_TYPE_IN_MY_FOLLOWING_LIST
                    callback.onSuccess(value: followed)
                }
            } fail: { code, message in
                self?.onMain {
                    callback.onFailure(code: code, message: message ?? "")
                }
            }
        }
    }

    /// 根据 shared 的目标状态调用关注或取消关注，并转换单用户操作结果。
    public func updateFollow(
        userId: String,
        followed: Bool,
        callback: GycLiveOperationCallback
    ) {
        onMain { [weak self] in
            let success: ([V2TIMFollowOperationResult]?) -> Void = { [weak self] results in
                self?.onMain {
                    guard let item = results?.first, item.resultCode == 0 else {
                        let item = results?.first
                        callback.onFailure(
                            code: Int32(clamping: item?.resultCode ?? -1),
                            message: item?.resultInfo ?? "关注操作失败"
                        )
                        return
                    }
                    callback.onSuccess()
                }
            }
            let failure: V2TIMFail = { [weak self] code, message in
                self?.onMain {
                    callback.onFailure(code: code, message: message ?? "")
                }
            }
            if followed {
                V2TIMManager.sharedInstance().followUser(
                    userIDList: [userId], succ: success, fail: failure
                )
            } else {
                V2TIMManager.sharedInstance().unfollowUser(
                    userIDList: [userId], succ: success, fail: failure
                )
            }
        }
    }

    /// 查询主播粉丝数并收敛为不含 V2TIM 类型的 Long 结果。
    public func fetchFans(userId: String, callback: GycLiveLongCallback) {
        onMain { [weak self] in
            V2TIMManager.sharedInstance().getUserFollowInfo(userIDList: [userId]) { [weak self] values in
                self?.onMain {
                    guard let item = values?.first, item.resultCode == 0 else {
                        let item = values?.first
                        callback.onFailure(
                            code: Int32(clamping: item?.resultCode ?? -1),
                            message: item?.resultInfo ?? "获取粉丝数失败"
                        )
                        return
                    }
                    callback.onSuccess(value: Int64(clamping: item.followersCount))
                }
            } fail: { code, message in
                self?.onMain {
                    callback.onFailure(code: code, message: message ?? "")
                }
            }
        }
    }

    /**
     * 释放完整观看会话的所有本地状态，并按需等待 SDK leaveLive 结果。
     *
     * 先清空 activeLiveID 和观察者可形成终态门禁，使迟到 Store/Combine 回调无法写回新页面。
     */
    @MainActor
    func releaseActiveAudience(
        leaveRoom: Bool,
        completion: ((_ code: Int?, _ message: String?) -> Void)? = nil
    ) {
        guard activeAudienceView != nil || activeLiveID != nil else {
            completion?(nil, nil)
            return
        }
        releasePictureInPicture(notify: true)
        audienceGeneration += 1
        liveEventCancellable?.cancel()
        liveEventCancellable = nil
        interactionSession.reset()
        activeAudienceView = nil
        activeLiveID = nil
        audienceObserver = nil
        if leaveRoom {
            LiveListStore.shared.leaveLive { [weak self] result in
                self?.onMain {
                    switch result {
                    case .success:
                        completion?(nil, nil)
                    case .failure(let error):
                        completion?(error.code, error.message)
                    }
                }
            }
        } else {
            completion?(nil, nil)
        }
    }

    /// 将异步 SDK 回调切到 MainActor；调用方不应在闭包中执行阻塞任务。
    func onMain(_ block: @escaping @MainActor () -> Void) {
        LiveMainThread.run(block)
    }

    /**
     * 同步读取或创建 UIKit/AtomicX 对象。
     *
     * 仅用于协议要求同步返回值的方法，主线程外调用会短暂阻塞等待 MainActor，
     * 闭包必须保持轻量。
     */
    func onMainSync<T>(_ block: @escaping @MainActor () -> T) -> T {
        LiveMainThread.syncOnMainActor(block)
    }
}
