import UIKit
import LiveKuikly
import OpenKuiklyIOSRender
@main struct Consumer {
 static func main() {
  let view = GycLiveView(frame: .zero)
  view.hrv_setProp(withKey: "room", propValue: "{\"liveId\":\"compile-only\",\"preview\":true,\"active\":false}")
  print(type(of: view))
 }
}
