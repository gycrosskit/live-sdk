import XCTest
import UIKit
import OpenKuiklyIOSRender
import GycLiveNative

private final class Controller: GycLiveRendererController {
    let view = UIView()
    let emit: (String) -> Void
    var rooms: [String] = []
    var visibility: [Bool] = []
    var commands: [String] = []
    var releases = 0
    init(emit: @escaping (String) -> Void) { self.emit = emit }
    func configure(value: String) { rooms.append(value) }
    func setVisible(value: Bool) { visibility.append(value) }
    func command(method: String, params: String, callback: @escaping (String) -> Void) {
        commands.append("\(method):\(params)")
        callback("{\"success\":true}")
    }
    func release() {
        releases += 1
        emit("{\"type\":\"duringRelease\"}")
    }
}

final class RendererTests: XCTestCase {
    // 经真实 CSS selector 传递 Objective-C Block，避免 Swift 闭包直调掩盖转换回归。
    private func bind(_ view: GycLiveRendererView, _ receive: @escaping ([String: Any]) -> Void) {
        let block: @convention(block) (Any?) -> Void = { receive($0 as? [String: Any] ?? [:]) }
        view.hrv_setProp(withKey: "liveEvent", propValue: block as AnyObject)
    }

    private func room(_ view: GycLiveRendererView, _ id: String) {
        view.hrv_setProp(withKey: "room", propValue: "{\"liveId\":\"\(id)\",\"active\":false}")
    }

    func testMissingFactoryFailsAndUnreadyCommandsKeepFailureContract() {
        XCTAssertTrue(Thread.isMainThread)
        let view = GycLiveRendererView(frame: .zero)
        var events: [[String: Any]] = []
        room(view, "a")
        bind(view) { events.append($0) }
        XCTAssertEqual(events.count, 1)
        XCTAssertEqual(events.first?["type"] as? String, "joinFailed")
        XCTAssertEqual(events.first?["code"] as? Int, -1)
        var pipReply: [String: Any]?
        view.hrv_call(withMethod: "enterPictureInPicture", params: nil) { pipReply = $0 as? [String: Any] }
        XCTAssertEqual(pipReply?["accepted"] as? Bool, false)
        var barrageReply: [String: Any]?
        view.hrv_call(withMethod: "sendBarrage", params: nil) { barrageReply = $0 as? [String: Any] }
        XCTAssertEqual(barrageReply?["success"] as? Bool, false)
    }

    func testObjectiveCBlockReplaysOnlyLast16Events() {
        let view = GycLiveRendererView(frame: .zero)
        var controller: Controller!
        view.controllerFactory = { controller = Controller(emit: $0); return controller }
        room(view, "a")
        for index in 0..<20 { controller.emit("{\"index\":\(index)}") }
        var received: [Int] = []
        bind(view) { received.append($0["index"] as? Int ?? -1) }
        XCTAssertEqual(received, Array(4..<20))
        controller.emit("{\"index\":20}")
        XCTAssertEqual(received.last, 20)
        view.hrv_removeFromSuperview()
    }

    func testRoomChangeDropsOldBacklogAndReentrantReplayStops() {
        let view = GycLiveRendererView(frame: .zero)
        var controller: Controller!
        view.controllerFactory = { controller = Controller(emit: $0); return controller }
        room(view, "a")
        controller.emit("{\"index\":0}")
        room(view, "b")
        controller.emit("{\"index\":1}")
        controller.emit("{\"index\":2}")
        var received: [Int] = []
        bind(view) {
            received.append($0["index"] as? Int ?? -1)
            self.room(view, "c")
        }
        XCTAssertEqual(received, [1])
        XCTAssertEqual(controller.rooms.count, 3)
        view.hrv_removeFromSuperview()
    }

    func testCommandsVisibilityAndDestroyShareOneController() {
        let view = GycLiveRendererView(frame: .zero)
        var controllers: [Controller] = []
        view.controllerFactory = {
            let controller = Controller(emit: $0)
            controllers.append(controller)
            return controller
        }
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 100, height: 100))
        window.addSubview(view)
        room(view, "a")
        let first = controllers[0]
        XCTAssertTrue(first.view.superview === view)
        NotificationCenter.default.post(name: UIApplication.willResignActiveNotification, object: nil)
        XCTAssertEqual(first.visibility.last, false)
        NotificationCenter.default.post(name: UIApplication.didBecomeActiveNotification, object: nil)
        XCTAssertEqual(first.visibility.last, true)
        var success = false
        view.hrv_call(withMethod: "sendBarrage", params: "{\"message\":\"a\"}") {
            success = ($0 as? [String: Any])?["success"] as? Bool ?? false
        }
        XCTAssertTrue(success)
        XCTAssertEqual(first.commands, ["sendBarrage:{\"message\":\"a\"}"])
        var events: [String] = []
        bind(view) { events.append($0["type"] as? String ?? "") }
        view.hrv_removeFromSuperview()
        XCTAssertEqual(first.releases, 1)
        XCTAssertNil(first.view.superview)
        XCTAssertTrue(events.isEmpty)
        let visibilityCount = first.visibility.count
        NotificationCenter.default.post(name: UIApplication.didBecomeActiveNotification, object: nil)
        XCTAssertEqual(first.visibility.count, visibilityCount)
        room(view, "b")
        bind(view) { events.append($0["type"] as? String ?? "") }
        first.emit("{\"type\":\"late\"}")
        controllers[1].emit("{\"type\":\"current\"}")
        XCTAssertEqual(events, ["current"])
        XCTAssertEqual(controllers.count, 2)
        view.hrv_removeFromSuperview()
        view.hrv_removeFromSuperview()
        XCTAssertEqual(controllers[1].releases, 1)
    }

    func testFactoryCallbackCanDestroyNodeBeforeControllerIsReturned() {
        let view = GycLiveRendererView(frame: .zero)
        var controller: Controller!
        bind(view) { _ in view.hrv_removeFromSuperview() }
        view.controllerFactory = {
            controller = Controller(emit: $0)
            $0("{\"type\":\"synchronous\"}")
            return controller
        }
        room(view, "a")
        XCTAssertEqual(controller.releases, 1)
        XCTAssertTrue(controller.rooms.isEmpty)
        XCTAssertNil(controller.view.superview)
        view.hrv_removeFromSuperview()
        XCTAssertEqual(controller.releases, 1)
    }

    func testDeinitReleasesControllerAndDetachesObservers() {
        var controller: Controller!
        weak var releasedView: GycLiveRendererView?
        autoreleasepool {
            let view = GycLiveRendererView(frame: .zero)
            releasedView = view
            view.controllerFactory = { controller = Controller(emit: $0); return controller }
            room(view, "a")
        }
        XCTAssertNil(releasedView)
        XCTAssertEqual(controller.releases, 1)
        let count = controller.visibility.count
        NotificationCenter.default.post(name: UIApplication.didBecomeActiveNotification, object: nil)
        XCTAssertEqual(controller.visibility.count, count)
    }
}
