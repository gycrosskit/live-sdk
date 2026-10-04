import UIKit

/// 原生操作回执；SDK 错误只交给宿主，不直接展示原始文案。
public protocol GycLiveOperationCallback {
    func onSuccess()
    func onFailure(code: Int32, message: String)
}

public protocol GycLiveBooleanCallback {
    func onSuccess(value: Bool)
    func onFailure(code: Int32, message: String)
}

public protocol GycLiveLongCallback {
    func onSuccess(value: Int64)
    func onFailure(code: Int32, message: String)
}

/// 不暴露 AtomicX 或 IM 类型，UIKit 应用和不同 KMP 导出模块均可接入。
public protocol GycAudiencePlayerObserver {
    func onJoinSucceeded()
    func onLiveInfo(roomId: String, liveName: String, notice: String, ownerId: String,
                    ownerName: String, ownerAvatarUrl: String)
    func onJoinFailed(code: Int32, message: String)
    func onLiveEnded()
    func onKickedOut()
    func onInteractionReady()
    func onBarrageReceived(sequence: Int64, timestampSeconds: Double, senderId: String,
                           senderName: String, senderAvatarUrl: String, content: String)
    func onLikesReceived(count: Int32)
    func onAudienceChanged(userIds: [String], userNames: [String], userAvatarUrls: [String], count: Int32)
    func onMemberChanged(joined: Bool, userId: String, userName: String,
                         userAvatarUrl: String, timestampSeconds: Double)
    func onAudienceMessageDisabled(userId: String, disabled: Bool)
    /// 表示 RoomEngine 实验接口响应；不能据此推断系统浮窗已可见。
    func onPictureInPictureChanged(enabled: Bool)
}

public protocol GycLivePreviewObserver {
    func onLoading()
    func onPlaying()
    func onFailed(code: Int32, message: String)
}

/// UTF-8 payload 原样交给宿主协议解析器；组件只做连接归属和群组过滤。
public protocol GycLiveImObserver {
    func onRestCustomData(groupId: String, payload: String)
    func onCurrentUserRemoved(groupId: String, operatorUserId: String)
    func onGroupDismissed(groupId: String)
    func onKickedOffline()
    func onUserSigExpired()
}

public enum GycLiveLogLevel { case info, warning, error }
