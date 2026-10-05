package io.github.gycrosskit.liveconsumer
import android.app.Activity
import android.os.Bundle
import io.github.gycrosskit.livesdk.GycLiveView
class MainActivity : Activity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  setContentView(GycLiveView(this))
 }
}
