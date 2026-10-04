import RTCRoomEngine
import UIKit

/** RoomEngine 系统 PiP 接线；前台应用内小窗仍由 `AudiencePresentationBridge` 管理。 */
extension GycLiveClient {
    /// 返回值只代表请求已提交；实际启用结果通过 audienceObserver 异步通知 shared。
    public func enterPictureInPicture(wideContent: Bool) -> Bool {
        onMainSync { [self] in
            guard let liveID = activeLiveID,
                  activeAudienceView?.window != nil else {
                log(.warning, "iOS 画中画请求被拒绝：观看会话或播放 View 尚未就绪")
                return false
            }
            return configurePictureInPicture(
                enabled: true,
                liveID: liveID,
                wideContent: wideContent,
                notify: true
            )
        }
    }

    /// 后台准备由 UIKit 容器接收结果，避免把准备阶段误报为 shared 已进入系统 PiP。
    public func enableBackgroundPictureInPicture(
        wideContent: Bool,
        completion: @escaping (Bool) -> Void
    ) -> Bool {
        onMainSync { [self] in
            guard let liveID = activeLiveID,
                  activeAudienceView?.window != nil else {
                completion(false)
                return false
            }
            return configurePictureInPicture(
                enabled: true,
                liveID: liveID,
                wideContent: wideContent,
                notify: false,
                completion: completion
            )
        }
    }

    public func disableBackgroundPictureInPicture() {
        onMain { [weak self] in
            self?.releasePictureInPicture(notify: false)
        }
    }

    /** 关闭 RoomEngine 管理的系统 PiP；退出直播时必须先关闭，避免 SDK 残留后台播放状态。 */
    @MainActor
    func releasePictureInPicture(notify: Bool) {
        guard pictureInPictureEnabled, let liveID = activeLiveID else {
            if notify { audienceObserver?.onPictureInPictureChanged(enabled: false) }
            return
        }
        _ = configurePictureInPicture(
            enabled: false,
            liveID: liveID,
            wideContent: false,
            notify: notify
        )
    }

    /** 使用 RoomEngine 带 `room_id` 的实验接口路由到当前直播子播放器。 */
    @MainActor
    private func configurePictureInPicture(
        enabled: Bool,
        liveID: String,
        wideContent: Bool,
        notify: Bool,
        completion: ((Bool) -> Void)? = nil
    ) -> Bool {
        let width = wideContent ? 1280 : 720
        let height = wideContent ? 720 : 1280
        let jsonObject: [String: Any] = [
            "api": "enablePictureInPicture",
            "params": [
                "enable": enabled,
                "room_id": liveID,
                "canvas": [
                    "width": width,
                    "height": height,
                    "backgroundColor": "#0f1014",
                ],
                "regions": [[
                    "userId": "",
                    "userName": "",
                    "backgroundColor": "",
                    "width": 1.0,
                    "height": 1.0,
                    "x": 0.0,
                    "y": 0.0,
                    "fillMode": 1,
                    "streamType": "high",
                    "backgroundImage": "",
                ]],
            ],
        ]
        guard let jsonData = try? JSONSerialization.data(withJSONObject: jsonObject),
              let jsonString = String(data: jsonData, encoding: .utf8) else {
            log(.error, "iOS 画中画参数序列化失败")
            if notify { audienceObserver?.onPictureInPictureChanged(enabled: false) }
            completion?(false)
            return false
        }

        pictureInPictureRequestSerial += 1
        let requestSerial = pictureInPictureRequestSerial
        let generation = audienceGeneration
        pictureInPictureEnabled = enabled
        TUIRoomEngine.sharedInstance().callExperimentalAPI(jsonStr: jsonString) { [weak self] response in
            self?.onMain {
                guard let self, requestSerial == self.pictureInPictureRequestSerial,
                      generation == self.audienceGeneration, self.activeLiveID == liveID else { return }
                let success = self.isPictureInPictureResponseSuccessful(response)
                self.pictureInPictureEnabled = enabled && success
                let message = "iOS RoomEngine 画中画响应，enable=\(enabled), success=\(success)"
                if success {
                    self.log(.info, message)
                } else {
                    self.log(.warning, message)
                }
                if notify {
                    self.audienceObserver?.onPictureInPictureChanged(enabled: enabled && success)
                }
                completion?(enabled && success)
            }
        }
        return true
    }

    /** 只把明确错误判为失败，兼容 RoomEngine 旧版空响应。 */
    private func isPictureInPictureResponseSuccessful(_ response: String) -> Bool {
        let normalized = response.lowercased()
        if normalized.contains("illegal api") ||
            normalized.contains("error") ||
            normalized.contains("fail") {
            return false
        }
        guard let data = response.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return true
        }
        if let code = object["code"] as? NSNumber { return code.intValue == 0 }
        if let result = object["result"] as? NSNumber { return result.intValue == 0 }
        return true
    }
}
