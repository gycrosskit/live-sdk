package io.github.gycrosskit.livesdk

/**
 * 旧客户端兼容表情的展示值与收发 token。
 *
 * @property display Unicode 展示字符，可能含多个码点；图片、字体与编辑行为由宿主决定。
 * @property token 发送时使用的兼容协议原文，例如 `[TUIEmoji_Smile]`。
 */
data class LiveEmojiValue(val display: String, val token: String)

/**
 * CMP/Kuikly 共用的直播兼容表情协议，不依赖 SDK 登录或 UI 引擎。
 *
 * 保留既有客户端的 62 项映射，不代表厂商官方 token 标准全集；未知表情和 token 保留原文。
 */
object LiveEmojiProtocol {
    /** 既有表情目录顺序；宿主可以按此顺序关联自己的图片，但不能依赖组件提供资源。 */
    val items = listOf(
        LiveEmojiValue("🙂", "[TUIEmoji_Smile]"),
        LiveEmojiValue("🤗", "[TUIEmoji_Expect]"),
        LiveEmojiValue("😉", "[TUIEmoji_Blink]"),
        LiveEmojiValue("😆", "[TUIEmoji_Guffaw]"),
        LiveEmojiValue("😊", "[TUIEmoji_KindSmile]"),
        LiveEmojiValue("😁", "[TUIEmoji_Haha]"),
        LiveEmojiValue("😄", "[TUIEmoji_Cheerful]"),
        LiveEmojiValue("😓", "[TUIEmoji_Speechless]"),
        LiveEmojiValue("😮", "[TUIEmoji_Amazed]"),
        LiveEmojiValue("😢", "[TUIEmoji_Sorrow]"),
        LiveEmojiValue("😎", "[TUIEmoji_Complacent]"),
        LiveEmojiValue("🤪", "[TUIEmoji_Silly]"),
        LiveEmojiValue("😍", "[TUIEmoji_Lustful]"),
        LiveEmojiValue("😋", "[TUIEmoji_Giggle]"),
        LiveEmojiValue("😘", "[TUIEmoji_Kiss]"),
        LiveEmojiValue("😭", "[TUIEmoji_Wail]"),
        LiveEmojiValue("😂", "[TUIEmoji_TearsLaugh]"),
        LiveEmojiValue("😴", "[TUIEmoji_Trapped]"),
        LiveEmojiValue("😷", "[TUIEmoji_Mask]"),
        LiveEmojiValue("😨", "[TUIEmoji_Fear]"),
        LiveEmojiValue("😬", "[TUIEmoji_BareTeeth]"),
        LiveEmojiValue("😡", "[TUIEmoji_FlareUp]"),
        LiveEmojiValue("🥱", "[TUIEmoji_Yawn]"),
        LiveEmojiValue("😏", "[TUIEmoji_Tact]"),
        LiveEmojiValue("🤩", "[TUIEmoji_Stareyes]"),
        LiveEmojiValue("🤐", "[TUIEmoji_ShutUp]"),
        LiveEmojiValue("😮‍💨", "[TUIEmoji_Sigh]"),
        LiveEmojiValue("🙃", "[TUIEmoji_Hehe]"),
        LiveEmojiValue("😑", "[TUIEmoji_Silent]"),
        LiveEmojiValue("😲", "[TUIEmoji_Surprised]"),
        LiveEmojiValue("🙄", "[TUIEmoji_Askance]"),
        LiveEmojiValue("👌", "[TUIEmoji_Ok]"),
        LiveEmojiValue("💩", "[TUIEmoji_Shit]"),
        LiveEmojiValue("👾", "[TUIEmoji_Monster]"),
        LiveEmojiValue("👿", "[TUIEmoji_Daemon]"),
        LiveEmojiValue("🤬", "[TUIEmoji_Rage]"),
        LiveEmojiValue("🤡", "[TUIEmoji_Fool]"),
        LiveEmojiValue("🐷", "[TUIEmoji_Pig]"),
        LiveEmojiValue("🐮", "[TUIEmoji_Cow]"),
        LiveEmojiValue("🤖", "[TUIEmoji_Ai]"),
        LiveEmojiValue("💀", "[TUIEmoji_Skull]"),
        LiveEmojiValue("💣", "[TUIEmoji_Bombs]"),
        LiveEmojiValue("☕", "[TUIEmoji_Coffee]"),
        LiveEmojiValue("🎂", "[TUIEmoji_Cake]"),
        LiveEmojiValue("🍺", "[TUIEmoji_Beer]"),
        LiveEmojiValue("🌹", "[TUIEmoji_Flower]"),
        LiveEmojiValue("🍉", "[TUIEmoji_Watermelon]"),
        LiveEmojiValue("🤑", "[TUIEmoji_Rich]"),
        LiveEmojiValue("❤️", "[TUIEmoji_Heart]"),
        LiveEmojiValue("🌙", "[TUIEmoji_Moon]"),
        LiveEmojiValue("☀️", "[TUIEmoji_Sun]"),
        LiveEmojiValue("⭐", "[TUIEmoji_Star]"),
        LiveEmojiValue("🧧", "[TUIEmoji_RedPacket]"),
        LiveEmojiValue("🎉", "[TUIEmoji_Celebrate]"),
        LiveEmojiValue("㊗️", "[TUIEmoji_Bless]"),
        LiveEmojiValue("🀄", "[TUIEmoji_Fortune]"),
        LiveEmojiValue("🫡", "[TUIEmoji_Convinced]"),
        LiveEmojiValue("🚫", "[TUIEmoji_Prohibit]"),
        LiveEmojiValue("6️⃣", "[TUIEmoji_666]"),
        LiveEmojiValue("8️⃣", "[TUIEmoji_857]"),
        LiveEmojiValue("🔪", "[TUIEmoji_Knife]"),
        LiveEmojiValue("👍", "[TUIEmoji_Like]"),
    )
    private val encodingOrder = items.sortedByDescending { it.display.length }

    /** 将已知展示值转为 token，优先替换最长组合字符；已编码 token 与其他文本保持原样。 */
    fun encode(content: String): String = encodingOrder.fold(content) { value, emoji -> value.replace(emoji.display, emoji.token) }

    /** 将已知 token 转为展示值；未知 token 保留原文，避免吞掉新协议内容。 */
    fun decode(content: String): String = items.fold(content) { value, emoji -> value.replace(emoji.token, emoji.display) }
}
