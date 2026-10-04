import Foundation
public protocol GycLiveOperationCallback { func onSuccess(); func onFailure(code: Int32, message: String) }
final class LocalViewSession { func stop() {} }
public final class GycLiveClient {
    var preparedLiveIdentity: LiveSdkIdentity?
    var ownsTencentRuntime = false
    var accountOperationSerial = 0
    let previewSession = LocalViewSession()
    func releaseActiveAudience(leaveRoom: Bool) {}
    func detachLiveImListeners() {}
    func onMain(_ action: @escaping @MainActor () -> Void) { MainActor.assumeIsolated(action) }
    func onMainSync<T>(_ action: @escaping @MainActor () -> T) -> T { MainActor.assumeIsolated(action) }
}
