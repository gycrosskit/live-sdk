public enum LoginStatus: Int { case loggedIn = 1, loggingIn = 2, loggedOut = 3 }
public final class V2TIMManager {
    public static let instance = V2TIMManager()
    public var actualUser: String?
    public var actualStatus: LoginStatus = .loggedOut
    public static func sharedInstance() -> V2TIMManager? { instance }
    public func getLoginUser() -> String? { actualUser }
    public func getLoginStatus() -> LoginStatus { actualStatus }
}
