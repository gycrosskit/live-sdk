package io.github.gycrosskit.liveconsumer

import io.github.gycrosskit.livesdk.AndroidLiveImSource
import io.github.gycrosskit.livesdk.LiveImObserver

/** 仅编译消费公开签名；示例 UI 不自行注册业务 IM。 */
internal fun consumeLiveIm(source: AndroidLiveImSource, observer: LiveImObserver) {
    source.connect(setOf("consumer-room"), observer)
    source.disconnect()
    source.close()
}
