package io.github.gycrosskit.livesdk

import kotlinx.coroutines.flow.MutableStateFlow

/** 中立原生 IM 回调；群业务 JSON、房间状态和提示留在宿主 router。 */
interface LiveImObserver {
    fun onRestCustomData(groupId: String, payload: String)
    fun onCurrentUserRemoved(groupId: String, operatorUserId: String)
    fun onGroupDismissed(groupId: String)
    fun onKickedOffline()
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
