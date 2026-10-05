package io.github.gycrosskit.liveconsumer
import com.tencent.kuikly.core.base.ViewContainer
import io.github.gycrosskit.livesdk.LiveVideo
fun ViewContainer<*, *>.consume() {
 LiveVideo {
  attr { room("demo-room", preview = true, active = false); size(200f, 100f) }
  event { liveEvent { } }
  enterPictureInPicture(wideContent = true) { accepted -> if (accepted) Unit }
  updatePictureInPicture(false)
 }
}
