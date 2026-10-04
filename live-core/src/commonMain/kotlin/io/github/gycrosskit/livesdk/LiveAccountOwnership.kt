package io.github.gycrosskit.livesdk

internal data class LiveSdkIdentity(val sdkAppId: Int, val userId: String)
internal enum class LiveAccountAction { REUSE, INITIALIZE, RESET, REJECT }

/** prepared 不代表 runtime owned：同用户外部登录可借用，不能取得注销权限。 */
internal fun liveAccountPreparationAction(
    prepared: LiveSdkIdentity?,
    ownsRuntime: Boolean,
    actualUser: String?,
    target: LiveSdkIdentity,
    sdkReady: Boolean,
    configuredSdkAppId: Int = prepared?.sdkAppId ?: 0,
): LiveAccountAction {
    val ownsActual = liveOwnsActualIdentity(prepared, ownsRuntime, actualUser, configuredSdkAppId)
    return when {
        actualUser != null && actualUser != target.userId && !ownsActual -> LiveAccountAction.REJECT
        configuredSdkAppId > 0 && configuredSdkAppId != target.sdkAppId && actualUser != null && !ownsActual -> LiveAccountAction.REJECT
        ownsActual && prepared != target -> LiveAccountAction.RESET
        prepared == target && actualUser == target.userId && sdkReady -> LiveAccountAction.REUSE
        else -> LiveAccountAction.INITIALIZE
    }
}
internal fun liveOwnsActualIdentity(prepared: LiveSdkIdentity?, ownsRuntime: Boolean, actualUser: String?, configuredSdkAppId: Int = prepared?.sdkAppId ?: 0): Boolean =
    ownsRuntime && prepared != null && actualUser == prepared.userId &&
        (configuredSdkAppId <= 0 || configuredSdkAppId == prepared.sdkAppId)
