import UIKit
import OpenKuiklyIOSRender

/// 只持有 Renderer 节点；Kotlin 导出类型由消费方的薄 adapter 映射。
public protocol GycLiveRendererController: AnyObject {
    var view: UIView { get }
    func configure(value: String)
    func setVisible(value: Bool)
    func command(method: String, params: String, callback: @escaping (String) -> Void)
    func release()
}

/// 不绑定 KMP Framework 名称的 Kuikly 视频节点；由宿主薄类注册原生名称。
open class GycLiveRendererView: UIView, KuiklyRenderViewExportProtocol {
    /// 首次 room 前注入；只包装应用现有的 Kotlin controller，不另建直播会话。
    public var controllerFactory: ((_ onEvent: @escaping (String) -> Void) -> GycLiveRendererController)?
    private var event: KuiklyRenderCallback?
    private var pendingEvents: [String] = []
    private var roomIdentity: String?
    private var controller: GycLiveRendererController?
    private var notifications: [NSObjectProtocol] = []
    private var generation = 0

    // Objective-C Block 必须经有类型的 setter 桥接，从 Any 转为 Swift 闭包会丢失回调。
    @objc(setCss_liveEvent:)
    public func setLiveEvent(_ callback: KuiklyRenderCallback?) {
        event = callback
        guard callback != nil else { return }
        let pending = pendingEvents
        pendingEvents.removeAll()
        let owner = generation
        let identity = roomIdentity
        for json in pending {
            guard generation == owner, roomIdentity == identity else { break }
            emit(json)
        }
    }

    /// Main 应用房间/事件属性，事件注册前最多保留 16 条当前会话事件。
    public func hrv_setProp(withKey propKey: String, propValue: Any) {
        if css_setProp(withKey: propKey, value: propValue) { return }
        if propKey == "room", let room = propValue as? String {
            let config = Self.dictionary(room)
            if let id = config["liveId"] as? String, !id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                let preview = config["preview"] as? Bool ?? false
                let identity = "\(id.utf8.count):\(id):\(preview)"
                // 事件注册可能晚于换房；旧房间缓存不能随后回放到新页面。
                if roomIdentity != identity { pendingEvents.removeAll(); roomIdentity = identity }
            }
            if controller == nil {
                guard let factory = controllerFactory else {
                    emit("{\"type\":\"joinFailed\",\"code\":-1,\"message\":\"iOS live renderer controller is not installed\"}")
                    return
                }
                generation += 1
                let owner = generation
                let next = factory { [weak self] json in
                    guard let self, self.generation == owner else { return }
                    self.emit(json)
                }
                guard generation == owner else {
                    next.release()
                    return
                }
                controller = next
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

    open override func didMoveToWindow() {
        super.didMoveToWindow()
        controller?.setVisible(value: window != nil && UIApplication.shared.applicationState == .active)
    }

    open override func hrv_removeFromSuperview() {
        // 先撤销旧 controller 的回调资格，再释放；迟到事件不能进入重建后的节点。
        generation += 1
        controller?.release()
        controller?.view.removeFromSuperview()
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
