package io.github.gycrosskit.livesdk

import kotlin.test.Test
import kotlin.test.assertEquals

class LiveEmojiProtocolTest {
    @Test
    fun `combination emoji is encoded before its shorter prefix`() {
        assertEquals(
            "[TUIEmoji_Sigh][TUIEmoji_Amazed][TUIEmoji_Heart][TUIEmoji_666][TUIEmoji_857]",
            LiveEmojiProtocol.encode("😮‍💨😮❤️6️⃣8️⃣"),
        )
    }

    @Test
    fun `unknown tokens and unsupported emoji survive alongside known tokens`() {
        val protocol = "前[TUIEmoji_Unknown]🙂[TUIEmoji_Heart]🫠[TUIEmoji_Smile]后"
        assertEquals("前[TUIEmoji_Unknown]🙂❤️🫠🙂后", LiveEmojiProtocol.decode(protocol))
        assertEquals(
            "前[TUIEmoji_Unknown][TUIEmoji_Smile][TUIEmoji_Heart]🫠[TUIEmoji_Smile]后",
            LiveEmojiProtocol.encode(protocol),
        )
    }

    @Test
    fun `plain text and empty content are unchanged`() {
        for (content in listOf("", "中文 abc 𠮷\n[custom] [TUIEmoji_Unknown]", "🫠")) {
            assertEquals(content, LiveEmojiProtocol.encode(content))
            assertEquals(content, LiveEmojiProtocol.decode(content))
        }
    }

    @Test
    fun `catalog preserves compatibility order and roundtrips all known entries`() {
        val items = LiveEmojiProtocol.items
        assertEquals(62, items.size)
        assertEquals(LiveEmojiValue("🙂", "[TUIEmoji_Smile]"), items.first())
        assertEquals(LiveEmojiValue("👍", "[TUIEmoji_Like]"), items.last())
        assertEquals(items.size, items.map { it.display }.toSet().size)
        assertEquals(items.size, items.map { it.token }.toSet().size)
        val display = "前" + items.joinToString("") { it.display } + "后"
        val tokens = "前" + items.joinToString("") { it.token } + "后"
        assertEquals(tokens, LiveEmojiProtocol.encode(display))
        assertEquals(display, LiveEmojiProtocol.decode(tokens))
        assertEquals(display, LiveEmojiProtocol.decode(LiveEmojiProtocol.encode(display)))
        assertEquals(tokens, LiveEmojiProtocol.encode(LiveEmojiProtocol.decode(tokens)))
    }

    @Test
    fun `encoding and decoding are idempotent for mixed content`() {
        val content = "消息😮‍💨 [TUIEmoji_Heart][TUIEmoji_Unknown]🙂"
        val encoded = LiveEmojiProtocol.encode(content)
        val decoded = LiveEmojiProtocol.decode(content)
        assertEquals(encoded, LiveEmojiProtocol.encode(encoded))
        assertEquals(decoded, LiveEmojiProtocol.decode(decoded))
    }
}
