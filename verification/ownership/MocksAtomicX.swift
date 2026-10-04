public enum LoginStatus { case logined, loggedOut }
public struct UserProfile {
    public let userID: String
    public init(userID: String, nickname: String, avatarURL: String) { self.userID = userID }
}
public struct LoginState {
    public var loginStatus: LoginStatus = .loggedOut
    public var loginUserInfo: UserProfile?
    public init() {}
}
public final class State { public var value = LoginState(); public init() {} }
public struct SdkError: Error { public let code: Int; public let message: String; public init() { code = -9; message = "mock" } }
public final class LoginStore {
    public static let shared = LoginStore()
    public let state = State()
    public var sdkAppID: Int32 = 100
    public var logins: [(Result<Void, SdkError>) -> Void] = []
    public var logouts: [(Result<Void, SdkError>) -> Void] = []
    public func login(sdkAppID: Int32, userID: String, userSig: String, completion: @escaping (Result<Void, SdkError>) -> Void) { logins.append(completion) }
    public func logout(completion: @escaping (Result<Void, SdkError>) -> Void) { logouts.append(completion) }
    public func setSelfInfo(userProfile: UserProfile, completion: ((Result<Void, SdkError>) -> Void)?) {}
}
