package io.github.gycrosskit.livesdk

import kotlinx.coroutines.flow.MutableStateFlow

/** 中立原生 IM 回调；群业务 JSON 留在宿主解析。Android 沿用 SDK 回调线程，观察者须自行调度 UI。 */
interface LiveImObserver {
    /**
     * 当前绑定群组的 UTF-8 REST 透传正文，组件不解释业务字段。
     *
     * @param groupId 当前绑定中的腾讯群组 ID。
     * @param payload UTF-8 透传正文，组件不解释业务字段。
     */
    fun onRestCustomData(groupId: String, payload: String)
    /**
     * 当前登录用户被移出绑定群组；[operatorUserId] 无值时为空。
     *
     * @param groupId 当前绑定中的腾讯群组 ID。
     * @param operatorUserId 移出操作者账号，SDK 未提供时为空。
     */
    fun onCurrentUserRemoved(groupId: String, operatorUserId: String)
    /**
     * 当前绑定群组已解散。
     *
     * @param groupId 当前绑定中的腾讯群组 ID。
     */
    fun onGroupDismissed(groupId: String)
    /** 当前连接被踢下线；通知前先撤销绑定，迟到终态不重复交付。 */
    fun onKickedOffline()
    /** 当前连接 UserSig 过期；凭据刷新与重新绑定由宿主负责。 */
    fun onUserSigExpired()
}

/** 同房重连用对象身份区分代次，终态原子失效；不持有 SDK 会话身份。 */
internal class LiveImBindingGate {
    internal class Binding(val groupIds: Set<String>, val observer: LiveImObserver)
    private val current = MutableStateFlow<Binding?>(null)
    fun connect(groupIds: Set<String>, observer: LiveImObserver): Binding =
        Binding(groupIds.map(String::trim).filter(String::isNotEmpty).toSet(), observer).also { current.value = it }
    fun accepts(binding: Binding, groupId: String): Boolean = current.value === binding && groupId in binding.groupIds
    fun takeTerminal(binding: Binding): LiveImObserver? = if (current.compareAndSet(binding, null)) binding.observer else null
    fun disconnect() { current.value = null }
}
