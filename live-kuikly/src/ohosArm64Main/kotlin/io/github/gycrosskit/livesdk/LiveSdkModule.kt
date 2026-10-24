@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package io.github.gycrosskit.livesdk

import com.tencent.kuikly.compose.coroutines.internal.KuiklyContextScheduler
import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 原生 SDK 错误码保持原样，宿主自行映射业务错误；消息不含身份或 UserSig。 */
class LiveSdkException(val code: Int, message: String) : Exception(message)

/** OHOS 每个 Renderer 的原生接线；SDK/账号唯一实例归 native，业务消息仍由宿主解释。 */
class LiveSdkModule : Module() {
    private class Pending(val completed: (Result<Unit>) -> Unit) { var callback: CallbackRef? = null }
    private class Observation(val id: String, val stopMethod: String) { var callback: CallbackRef? = null }
    private val pending = mutableSetOf<Pending>()
    private val observations = mutableSetOf<Observation>()
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var disposed = false
    private var nextId = 0L
    override fun moduleName() = NAME

    /** 页面线程调用；回调代表真实 SDK 完成，不能提前放行宿主账号操作队列。 */
    fun login(sdkAppId: Int, userId: String, userSig: String, nickname: String, avatarUrl: String,
        privacyGranted: Boolean, completed: (Result<Unit>) -> Unit) = request("login", JSONObject().apply {
        put("privacyGranted", privacyGranted); put("sdkAppId", sdkAppId); put("userId", userId)
        put("userSig", userSig); put("nickname", nickname); put("avatarUrl", avatarUrl)
    }, completed)

    /** 页面线程调用；注销等待真实原生清理，不清理借用的其他 SDK 身份。 */
    fun logout(completed: (Result<Unit>) -> Unit) = request("logout", JSONObject(), completed)

    private fun request(method: String, params: JSONObject, completed: (Result<Unit>) -> Unit) {
        if (disposed) { completed(Result.failure(IllegalStateException("Live host released"))); return }
        val entry = Pending(completed)
        pending.add(entry)
        try {
            entry.callback = toNative(false, method, params.toString(), { result ->
                if (pending.remove(entry)) {
                    discardCallback(entry.callback)
                    completed(if (result?.optBoolean("success") == true) Result.success(Unit)
                    else Result.failure(LiveSdkException(result?.optInt("code", -1) ?: -1,
                        result?.optString("message").orEmpty())))
                }
            }, false).callbackRef
            if (entry !in pending) discardCallback(entry.callback)
        } catch (_: Exception) {
            if (pending.remove(entry)) {
                discardCallback(entry.callback)
                completed(Result.failure(LiveSdkException(-1, "Live host invocation failed")))
            }
        }
    }

    /** Main 调用；恢复请求不冒充系统已恢复，真实状态仍来自 PiP 观察。 */
    fun restorePictureInPicture() {
        schedule { request("restorePiP", JSONObject()) { result ->
            if (result.isFailure) warning("直播画中画恢复请求失败")
        } }
    }

    /** 回调在 Main；取消函数立即失效本订阅，再在 Renderer 撤回原生监听。 */
    fun observePictureInPicture(changed: (Boolean) -> Unit, closed: () -> Unit): () -> Unit =
        observe("listenPiP", "unlistenPiP", JSONObject()) { event ->
            when (event.optString("type")) {
                "changed" -> changed(event.optBoolean("enabled"))
                "closed" -> closed()
            }
        }

    /** 中立 IM 事件复用 LiveImObserver；过滤和订阅失效在组件，业务解析及恢复策略在宿主。 */
    fun observeIm(groupIds: Set<String>, observer: LiveImObserver, onFailure: () -> Unit): () -> Unit {
        val groups = groupIds.map(String::trim).filter(String::isNotEmpty).toSet()
        return observe("listen", "unlisten", JSONObject().put("groups", JSONArray().apply {
            groups.sorted().forEach { put(it) }
        }), onFailure) { event ->
            val group = event.optString("groupId")
            when (event.optString("type")) {
                "data" -> if (group in groups) observer.onRestCustomData(group, event.optString("payload"))
                "removed" -> if (group in groups) observer.onCurrentUserRemoved(group, event.optString("operatorUserId"))
                "dismissed" -> if (group in groups) observer.onGroupDismissed(group)
                "kickedOffline" -> observer.onKickedOffline()
                "expired" -> observer.onUserSigExpired()
                "failed" -> onFailure()
            }
        }
    }

    private fun observe(method: String, stopMethod: String, params: JSONObject,
        onFailure: () -> Unit = {}, onEvent: (JSONObject) -> Unit): () -> Unit {
        if (disposed) { onFailure(); return {} }
        val observation = Observation((++nextId).toString(), stopMethod)
        observations.add(observation)
        schedule(onCancelled = { main.launch { if (!disposed && observations.remove(observation)) onFailure() } }) {
            if (observation !in observations) return@schedule
            try {
                observation.callback = toNative(true, method, params.put("id", observation.id).toString(), { event ->
                    if (event != null) main.launch {
                        if (!disposed && observation in observations) onEvent(event)
                    }
                }).callbackRef
                // 同步回执可能触发退出；新取得的引用仍归这次订阅清理。
                if (disposed || observation !in observations) cleanup(observation)
            } catch (_: Exception) {
                cleanup(observation)
                main.launch { if (!disposed && observations.remove(observation)) onFailure() }
            }
        }
        return {
            observations.remove(observation)
            KuiklyContextScheduler.runOnKuiklyThread(pagerId) { _ -> cleanup(observation) }
        }
    }

    private fun schedule(onCancelled: () -> Unit = {}, action: () -> Unit) {
        // shortcut: Kuikly 2.29.1 dispatcher 为 internal，升级时复核 Renderer 线程边界。
        KuiklyContextScheduler.runOnKuiklyThread(pagerId) { cancelled ->
            if (cancelled || disposed) onCancelled() else action()
        }
    }

    private fun cleanup(observation: Observation) {
        runCatching { asyncToNativeMethod(observation.stopMethod, JSONObject().put("id", observation.id), null) }
            .onFailure { warning("直播原生监听撤销失败") }
        discardCallback(observation.callback)
        observation.callback = null
    }

    private fun discardCallback(callback: CallbackRef?) {
        if (callback != null) runCatching { removeCallback(callback) }.onFailure { warning("直播回调引用释放失败") }
    }
    private fun warning(message: String) = AtomicAudienceRuntimeRegistry.log(AtomicAudienceLogLevel.WARNING, message)

    /** 页面线程幂等销毁。撤回本页监听/回调，不注销进程账号；root 生命周期负责 logout。 */
    fun dispose() {
        if (disposed) return
        disposed = true
        val observers = observations.toList(); observations.clear()
        observers.forEach(::cleanup)
        val requests = pending.toList(); pending.clear()
        requests.forEach {
            discardCallback(it.callback)
            it.completed(Result.failure(IllegalStateException("Live host released")))
        }
        main.cancel()
    }
    companion object { const val NAME = "GycLiveSdk" }
}
