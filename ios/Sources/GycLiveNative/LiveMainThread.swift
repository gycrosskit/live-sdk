import Foundation

/// 同步入口仅用于创建 UIView 或读取 SDK 状态，不能承载网络与文件任务。
enum LiveMainThread {
    static func run(_ action: @escaping @MainActor () -> Void) {
        if Thread.isMainThread {
            MainActor.assumeIsolated(action)
        } else {
            DispatchQueue.main.async { MainActor.assumeIsolated(action) }
        }
    }

    static func syncOnMainActor<T>(_ action: @escaping @MainActor () -> T) -> T {
        if Thread.isMainThread { return MainActor.assumeIsolated(action) }
        return DispatchQueue.main.sync { MainActor.assumeIsolated(action) }
    }
}
