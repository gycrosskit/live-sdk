package io.github.gycrosskit.liveconsumer

import io.github.gycrosskit.livesdk.LiveEmojiProtocol
import io.github.gycrosskit.livesdk.LiveEmojiValue

// 两种消费配置均从发布产物编译公共目录与收发 API。
fun consumeEmojiProtocol(content: String): String {
    val emoji: LiveEmojiValue = LiveEmojiProtocol.items.first()
    return LiveEmojiProtocol.decode(LiveEmojiProtocol.encode(content + emoji.display))
}
