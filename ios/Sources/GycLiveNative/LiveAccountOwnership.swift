import Foundation
struct LiveSdkIdentity: Equatable { let sdkAppId: Int32; let userId: String }
enum LiveAccountAction { case reuse, initialize, reset, reject }
func liveAccountPreparationAction(prepared: LiveSdkIdentity?, ownsRuntime: Bool, actualUser: String?, target: LiveSdkIdentity, sdkReady: Bool, configuredSdkAppId: Int32 = 0) -> LiveAccountAction {
    if let actualUser, actualUser != target.userId, !liveOwnsActualIdentity(prepared: prepared, ownsRuntime: ownsRuntime, actualUser: actualUser, configuredSdkAppId: configuredSdkAppId) { return .reject }
    let configured = configuredSdkAppId > 0 ? configuredSdkAppId : (prepared?.sdkAppId ?? 0)
    if configured > 0, configured != target.sdkAppId, actualUser != nil, !liveOwnsActualIdentity(prepared: prepared, ownsRuntime: ownsRuntime, actualUser: actualUser, configuredSdkAppId: configured) { return .reject }
    if let prepared, liveOwnsActualIdentity(prepared: prepared, ownsRuntime: ownsRuntime, actualUser: actualUser, configuredSdkAppId: configured), prepared != target { return .reset }
    if prepared == target, actualUser == target.userId, sdkReady { return .reuse }
    return .initialize
}
func liveOwnsActualIdentity(prepared: LiveSdkIdentity?, ownsRuntime: Bool, actualUser: String?, configuredSdkAppId: Int32 = 0) -> Bool {
    ownsRuntime && prepared != nil && actualUser == prepared?.userId &&
        (configuredSdkAppId <= 0 || configuredSdkAppId == prepared?.sdkAppId)
}
