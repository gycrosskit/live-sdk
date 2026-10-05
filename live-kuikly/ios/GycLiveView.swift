import UIKit
import OpenKuiklyIOSRender
import LiveKuikly

/// Kuikly Native DSL 视频节点，SDK 账号由现有 IosLiveSdkRuntime 提供。
@objc(GycLiveView)
public final class GycLiveView: UIView, KuiklyRenderViewExportProtocol {
    private var event: KuiklyRenderCallback?
    private var pendingEvents: [String] = []
    private var roomIdentity: String?
    private var controller: IosKuiklyLiveController?
    private var notifications: [NSObjectProtocol] = []
    /// 复用宿主业务点赞上报；新会话创建前注入，成功批次才上报。
    public var likeReporter: LiveLikeReporter? {
        didSet { if let value = likeReporter { controller?.likeReporter = value } }
    }

    /// Main 应用房间/事件属性，事件注册前最多保留 16 条当前会话事件。
    public func hrv_setProp(withKey propKey: String, propValue: Any) {
        if css_setProp(withKey: propKey, value: propValue) { return }
        if propKey == "liveEvent" {
            event = propValue as? KuiklyRenderCallback
            let pending = pendingEvents
            pendingEvents.removeAll()
            pending.forEach(emit)
        }
        else if propKey == "room", let room = propValue as? String {
            let config = Self.dictionary(room)
            if let id = config["liveId"] as? String, !id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                let preview = config["preview"] as? Bool ?? false
                let identity = "\(id.utf8.count):\(id):\(preview)"
                // 事件注册可能晚于换房；旧房间缓存不能随后回放到新页面。
                if roomIdentity != identity { pendingEvents.removeAll(); roomIdentity = identity }
            }
            if controller == nil {
                let next = IosKuiklyLiveController { [weak self] json in
                    self?.emit(json)
                }
                controller = next
                if let reporter = likeReporter { next.likeReporter = reporter }
                next.view.frame = bounds
                next.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
                addSubview(next.view)
                notifications = [
                    NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
                        self?.controller?.setVisible(value: self?.window != nil)
                    },
                    NotificationCenter.default.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in
                        self?.controller?.setVisible(value: false)
                    }
                ]
            }
            controller?.configure(value: room)
            controller?.setVisible(value: window != nil && UIApplication.shared.applicationState == .active)
        }
    }

    /// Main 将 Kuikly 原生命令交给共用 Kotlin 会话，弹幕回执转为字典。
    public func hrv_call(withMethod method: String, params: String?, callback: KuiklyRenderCallback?) {
        guard let controller else {
            if method == "enterPictureInPicture" { callback?(["accepted": false]) }
            else if method == "sendBarrage" { callback?(["success": false, "message": "Not ready"]) }
            return
        }
        controller.command(method: method, params: params ?? "{}") { json in
            callback?(Self.dictionary(json))
        }
    }

    public override func didMoveToWindow() {
        super.didMoveToWindow()
        controller?.setVisible(value: window != nil && UIApplication.shared.applicationState == .active)
    }

    public override func hrv_removeFromSuperview() {
        controller?.release()
        controller = nil
        event = nil
        pendingEvents.removeAll()
        roomIdentity = nil
        notifications.forEach(NotificationCenter.default.removeObserver)
        notifications = []
        super.hrv_removeFromSuperview()
    }

    deinit {
        notifications.forEach(NotificationCenter.default.removeObserver)
        controller?.release()
    }

    private static func dictionary(_ json: String) -> [String: Any] {
        guard let data = json.data(using: .utf8),
              let result = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
        return result
    }

    private func emit(_ json: String) {
        if let callback = event { callback(Self.dictionary(json)) }
        else {
            if pendingEvents.count == 16 { pendingEvents.removeFirst() }
            pendingEvents.append(json)
        }
    }
}
