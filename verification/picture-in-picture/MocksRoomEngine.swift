public final class TUIRoomEngine {
    public static let instance = TUIRoomEngine()
    public var replies: [(String) -> Void] = []
    public var requests: [String] = []
    public static func sharedInstance() -> TUIRoomEngine { instance }
    public func callExperimentalAPI(jsonStr: String, callback: @escaping (String) -> Void) {
        requests.append(jsonStr)
        replies.append(callback)
    }
}
