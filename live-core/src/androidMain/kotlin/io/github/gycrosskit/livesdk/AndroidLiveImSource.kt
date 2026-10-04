package io.github.gycrosskit.livesdk

import com.tencent.imsdk.v2.*
import java.io.Closeable

/** 唯一原生事件源；监听注册/撤回串行，迟到/异群回调不会交给新观察者。 */
class AndroidLiveImSource internal constructor(private val sdk: LiveImRegistration) : Closeable {
    constructor() : this(TencentLiveImRegistration)
    private val lock = Any()
    private val gate = LiveImBindingGate()
    private var groupListener: V2TIMGroupListener? = null
    private var sdkListener: V2TIMSDKListener? = null
    private var closed = false

    fun connect(groupIds: Set<String>, observer: LiveImObserver) = synchronized(lock) {
        check(!closed) { "IM source closed" }
        detach()
        val binding = gate.connect(groupIds, observer)
        val group = object : V2TIMGroupListener() {
            override fun onReceiveRESTCustomData(groupID: String?, customData: ByteArray?) = synchronized(lock) {
                if (groupID != null && customData != null && gate.accepts(binding, groupID))
                    binding.observer.onRestCustomData(groupID, customData.toString(Charsets.UTF_8))
            }
            override fun onMemberKicked(recvGroupID: String?, opUser: V2TIMGroupMemberInfo?, memberList: MutableList<V2TIMGroupMemberInfo>?) = synchronized(lock) {
                if (recvGroupID == null || !gate.accepts(binding, recvGroupID)) return@synchronized
                val userId = sdk.currentUserId()
                if (userId.isNotBlank() && memberList.orEmpty().any { it.userID == userId })
                    binding.observer.onCurrentUserRemoved(recvGroupID, opUser?.userID.orEmpty())
            }
            override fun onGroupDismissed(recvGroupID: String?, opUser: V2TIMGroupMemberInfo?) = synchronized(lock) {
                if (recvGroupID != null && gate.accepts(binding, recvGroupID)) binding.observer.onGroupDismissed(recvGroupID)
            }
        }
        val account = object : V2TIMSDKListener() {
            override fun onKickedOffline() = terminal(binding) { it.onKickedOffline() }
            override fun onUserSigExpired() = terminal(binding) { it.onUserSigExpired() }
        }
        groupListener = group; sdkListener = account
        try { sdk.add(group); sdk.add(account) }
        catch (failure: Throwable) {
            try { detach() } catch (cleanupFailure: Throwable) { failure.addSuppressed(cleanupFailure) }
            throw failure
        }
    }

    fun disconnect() = synchronized(lock) { detach() }
    override fun close() = synchronized(lock) { closed = true; detach() }

    private fun terminal(binding: LiveImBindingGate.Binding, event: (LiveImObserver) -> Unit) = synchronized(lock) {
        val observer = gate.takeTerminal(binding) ?: return@synchronized
        // 先撤回当前监听，再交业务回调；观察者即使重连也不会被旧终态清掉。
        try { detach() } finally { event(observer) }
    }
    private fun detach() {
        gate.disconnect()
        val group = groupListener; val account = sdkListener
        var failure: Throwable? = null
        // 失败的 handle 保留供 disconnect/close 重试；先失效 binding，旧 callback 不能复活。
        try { group?.let(sdk::remove); groupListener = null }
        catch (error: Throwable) { failure = error }
        try { account?.let(sdk::remove); sdkListener = null }
        catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        failure?.let { throw it }
    }
}

/** 薄厂商注册边界只用于替身验证；不缓存 SDK 登录身份。 */
internal interface LiveImRegistration {
    fun currentUserId(): String
    fun add(listener: V2TIMGroupListener)
    fun add(listener: V2TIMSDKListener)
    fun remove(listener: V2TIMGroupListener)
    fun remove(listener: V2TIMSDKListener)
}
private object TencentLiveImRegistration : LiveImRegistration {
    override fun currentUserId() = V2TIMManager.getInstance().loginUser.orEmpty()
    override fun add(listener: V2TIMGroupListener) = V2TIMManager.getInstance().addGroupListener(listener)
    override fun add(listener: V2TIMSDKListener) = V2TIMManager.getInstance().addIMSDKListener(listener)
    override fun remove(listener: V2TIMGroupListener) = V2TIMManager.getInstance().removeGroupListener(listener)
    override fun remove(listener: V2TIMSDKListener) = V2TIMManager.getInstance().removeIMSDKListener(listener)
}
