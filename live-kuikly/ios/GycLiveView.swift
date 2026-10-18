import UIKit
import GycLiveNative
import LiveKuikly

/// 默认 LiveKuikly 导出模块的薄接线；其他 KMP Framework 在应用侧映射同一 Swift 协议。
@objc(GycLiveView)
public final class GycLiveView: GycLiveRendererView {
    private weak var adapter: LiveKuiklyRendererController?
    /// 创建观看会话前设置；SDK 确认成功后才交给业务 reporter。
    public var likeReporter: LiveLikeReporter? {
        didSet { if let reporter = likeReporter { adapter?.controller.likeReporter = reporter } }
    }

    public override init(frame: CGRect) {
        super.init(frame: frame)
        installControllerFactory()
    }

    public required init?(coder: NSCoder) {
        super.init(coder: coder)
        installControllerFactory()
    }

    private func installControllerFactory() {
        controllerFactory = { [weak self] onEvent in
            let adapter = LiveKuiklyRendererController(onEvent: onEvent)
            if let reporter = self?.likeReporter { adapter.controller.likeReporter = reporter }
            self?.adapter = adapter
            return adapter
        }
    }
}

private final class LiveKuiklyRendererController: GycLiveRendererController {
    let controller: IosKuiklyLiveController
    init(onEvent: @escaping (String) -> Void) { controller = IosKuiklyLiveController(onEvent: onEvent) }
    var view: UIView { controller.view }
    func configure(value: String) { controller.configure(value: value) }
    func setVisible(value: Bool) { controller.setVisible(value: value) }
    func command(method: String, params: String, callback: @escaping (String) -> Void) {
        controller.command(method: method, params: params, callback: callback)
    }
    func release() { controller.release() }
}
