import UIKit
import RTCRoomEngine

enum GycLiveLogLevel { case warning, error, info }
final class Observer {
    var states: [Bool] = []
    func onPictureInPictureChanged(enabled: Bool) { states.append(enabled) }
}
public final class GycLiveClient {
    var activeLiveID: String? = "live"
    var activeAudienceView: UIView? = UIView()
    var audienceObserver: Observer? = Observer()
    var pictureInPictureEnabled = false
    var pictureInPictureRequestSerial = 0
    var audienceGeneration = 0
    func onMain(_ action: @escaping @MainActor () -> Void) { LiveMainThread.run(action) }
    func onMainSync<T>(_ action: @escaping @MainActor () -> T) -> T { LiveMainThread.syncOnMainActor(action) }
    @MainActor func log(_ level: GycLiveLogLevel, _ message: String) {}
}

let client = GycLiveClient()
assert(!client.enterPictureInPicture(wideContent: true))
client.activeAudienceView?.window = UIView()
assert(client.enterPictureInPicture(wideContent: true))
TUIRoomEngine.instance.replies.removeFirst()("{\"code\":0}")
assert(client.audienceObserver?.states.isEmpty == true, "SDK preparation cannot claim actual system PiP")
var prepared: Bool?
assert(client.enableBackgroundPictureInPicture(wideContent: false) { prepared = $0 })
TUIRoomEngine.instance.replies.removeFirst()("{\"code\":0}")
assert(prepared == true && client.audienceObserver?.states.isEmpty == true)
assert(client.enterPictureInPicture(wideContent: false))
client.audienceGeneration += 1
TUIRoomEngine.instance.replies.removeFirst()("{\"code\":-1}")
assert(client.pictureInPictureEnabled, "Old generation response cannot mutate current preparation")
MainActor.assumeIsolated { client.releasePictureInPicture(notify: true) }
TUIRoomEngine.instance.replies.removeFirst()("{\"code\":0}")
assert(client.audienceObserver?.states == [false])
print("PiP request receipt, preparation, actual-state separation and generation: PASS")
