import AtomicXCore
import UIKit

/**
 * 管理列表中唯一的 AtomicX 静音预览会话。
 *
 * 本对象只负责预览 View、liveID 和观察者的成对创建与释放，不调用 `joinLive`/`leaveLive`，
 * 因而不会干扰完整直播间的观看会话。所有方法必须在 MainActor 调用。
 */
final class AtomicXPreviewSession {
    /// 当前仍由 Compose/UIKitView 展示的预览 View；弱引用避免延长页面生命周期。
    private weak var activeView: LiveCoreView?
    /// 与 [activeView] 对应的直播 ID，用于拒绝旧 View 的迟到回调。
    private var activeLiveID: String?
    /// 当前预览状态观察者，必须与 View 和 liveID 同时释放。
    private var observer: GycLivePreviewObserver?

    /// 创建新的静音预览；创建前幂等停止上一条预览流。
    @MainActor
    func makeView(liveID: String, observer: GycLivePreviewObserver) -> UIView {
        stop()

        let coreView = LiveCoreView(viewType: .playView)
        coreView.backgroundColor = .black
        // 列表点击由外层 CMP 处理，SDK View 只负责绘制媒体内容。
        coreView.isUserInteractionEnabled = false
        coreView.clipsToBounds = true
        coreView.setLiveID(liveID)
        activeView = coreView
        activeLiveID = liveID
        self.observer = observer
        observer.onLoading()
        coreView.startPreviewLiveStream(
            roomId: liveID,
            isMuteAudio: true,
            onPlaying: { [weak self, weak coreView] _ in
                LiveMainThread.run {
                    guard let self,
                          let coreView,
                          self.activeView === coreView,
                          self.activeLiveID == liveID else { return }
                    self.observer?.onPlaying()
                }
            },
            onLoading: { [weak self, weak coreView] _ in
                LiveMainThread.run {
                    guard let self,
                          let coreView,
                          self.activeView === coreView,
                          self.activeLiveID == liveID else { return }
                    self.observer?.onLoading()
                }
            },
            onError: { [weak self, weak coreView] _, code, message in
                LiveMainThread.run {
                    guard let self,
                          let coreView,
                          self.activeView === coreView,
                          self.activeLiveID == liveID else { return }
                    self.observer?.onFailed(
                        code: Int32(clamping: code.rawValue),
                        message: message
                    )
                }
            }
        )
        return coreView
    }

    /// 仅当传入 View 仍属于当前会话时停止，避免迟到的 Compose dispose 误停新预览。
    @MainActor
    func release(view: UIView) {
        guard activeView === view else { return }
        stop()
    }

    /// 幂等停止当前预览流并清空全部会话引用。
    @MainActor
    func stop() {
        guard let activeView, let activeLiveID else {
            self.activeView = nil
            self.activeLiveID = nil
            observer = nil
            return
        }
        activeView.stopPreviewLiveStream(roomId: activeLiveID)
        self.activeView = nil
        self.activeLiveID = nil
        observer = nil
    }
}
