package io.github.gycrosskit.livesdk

import platform.UIKit.UIView

/** Swift AtomicX 操作完成回调；接口由 Shared 传递导出，具体 SDK 类型只存在于 iosApp。 */
interface IosLiveOperationCallback {
    fun onSuccess()

    fun onFailure(code: Int, message: String)
}

/** Swift IM 查询布尔结果时使用的稳定回调，不导出 V2TIM 类型。 */
interface IosLiveBooleanCallback {
    fun onSuccess(value: Boolean)

    fun onFailure(code: Int, message: String)
}

/** Swift IM 查询计数结果时使用的稳定回调。 */
interface IosLiveLongCallback {
    fun onSuccess(value: Long)

    fun onFailure(code: Int, message: String)
}

/** Swift 播放器把官方 SDK 事件转换为 live-sdk 已定义的稳定语义。 */
interface IosAudiencePlayerObserver {
    fun onJoinSucceeded()

    /** 进房成功后返回的直播信息，用于宽内容模式展示介绍，避免页面重复请求 SDK 数据。 */
    fun onLiveInfo(
        roomId: String,
        liveName: String,
        notice: String,
        ownerId: String,
        ownerName: String,
        ownerAvatarUrl: String,
    )

    fun onJoinFailed(code: Int, message: String)

    fun onLiveEnded()

    fun onLiveUnavailable(message: String)

    /** 观看期间被主播或管理员移出当前直播间；SDK 原始文案不进入业务 UI。 */
    fun onKickedOut()

    fun onInteractionReady()

    fun onBarrageReceived(
        sequence: Long,
        timestampSeconds: Double,
        senderId: String,
        senderName: String,
        senderAvatarUrl: String,
        content: String,
    )

    fun onLikesReceived(count: Int)

    fun onAudienceChanged(
        userIds: List<String>,
        userNames: List<String>,
        userAvatarUrls: List<String>,
        count: Int,
    )

    /** AtomicX 观众 Store 的成员进退房事件；Swift 不拼接最终展示文案。 */
    fun onMemberChanged(
        joined: Boolean,
        userId: String,
        userName: String,
        userAvatarUrl: String,
        timestampSeconds: Double,
    )

    /** 指定观众的弹幕权限变化；live-sdk 会过滤为当前登录用户。 */
    fun onAudienceMessageDisabled(userId: String, disabled: Boolean)

    /** 系统 PiP 的真实开始/停止回调；仅请求被接受不能作为 ACTIVE 状态。 */
    fun onPictureInPictureChanged(enabled: Boolean)
}

/** Swift 列表预览把 AtomicX 播放状态收敛为 shared 可消费的最小语义。 */
interface IosLivePreviewObserver {
    fun onLoading()

    fun onPlaying()

    fun onFailed(code: Int, message: String)
}

/** Swift 腾讯 IM 监听器向 shared 收敛的最小事件集合；消息正文仍由 shared 按后端契约解析。 */
interface IosLiveImObserver {
    fun onRestCustomData(groupId: String, payload: String)

    fun onCurrentUserRemoved(groupId: String, operatorUserId: String)

    fun onGroupDismissed(groupId: String)

    fun onKickedOffline()

    fun onUserSigExpired()
}

/**
 * iosApp 对 AtomicXCore 的唯一桥接边界。
 *
 * AtomicXCore 的 Store 主要以 Swift API 暴露，不能让 Kotlin/Native 直接依赖其二进制类型。Swift 只实现登录、
 * 进退房、互动命令和 `UIView` 生命周期；直播间可见操作层、提示和业务入口由 shared 统一维护。
 */
interface IosLiveSdkBridge {
    fun isLoggedInAs(userId: String): Boolean

    fun updateProfile(userId: String, nickname: String, avatarUrl: String)

    fun login(
        sdkAppId: Int,
        userId: String,
        userSig: String,
        nickname: String,
        avatarUrl: String,
        callback: IosLiveOperationCallback,
    )

    fun logout(callback: IosLiveOperationCallback)

    fun makePreviewView(liveId: String, observer: IosLivePreviewObserver): UIView

    fun releasePreviewView(view: UIView)

    fun stopPreview()

    fun connectLiveIm(groupIds: List<String>, observer: IosLiveImObserver)

    fun disconnectLiveIm()

    fun currentUserId(): String

    fun sendBarrage(message: String, callback: IosLiveOperationCallback)

    fun sendLike(count: Int, callback: IosLiveOperationCallback)

    fun checkFollowed(userId: String, callback: IosLiveBooleanCallback)

    fun updateFollow(userId: String, followed: Boolean, callback: IosLiveOperationCallback)

    fun fetchFans(userId: String, callback: IosLiveLongCallback)

    fun refreshAudience()

    /** 请求 TRTC 系统 PiP；返回值表示请求是否被接受，真实状态通过 observer 回调。 */
    fun enterPictureInPicture(wideContent: Boolean): Boolean

    fun makeAudienceView(liveId: String, observer: IosAudiencePlayerObserver): UIView

    /** 离房完成后才回调，common 会据此放行下一场观看或列表预览。 */
    fun releaseAudienceView(view: UIView, callback: IosLiveOperationCallback)
}
