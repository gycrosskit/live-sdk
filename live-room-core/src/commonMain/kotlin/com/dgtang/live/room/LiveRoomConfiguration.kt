package com.dgtang.live.room

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable enum class LiveSlot { HEADER, SIDE, FOOTER }
@Serializable enum class LiveBadgeStyle { NONE, DOT, COUNT, LABEL }
@Serializable enum class LivePresentation { CENTER, BOTTOM, HALF, FULL, PAGE }

/** 缺省文案与素材留在宿主资源；远端只能覆盖已注册能力的展示。 */
@Serializable
data class LiveEntryConfig(
    val visible: Boolean = true,
    val slot: LiveSlot? = null,
    val order: Int? = null,
    val text: Map<String, String> = emptyMap(),
    val iconUrl: String? = null,
    val badge: LiveBadgeStyle? = null,
    val badgeText: Map<String, String> = emptyMap(),
    val target: String? = null,
)

@Serializable
data class LiveSurfaceConfig(
    val presentation: LivePresentation? = null,
    val title: Map<String, String> = emptyMap(),
    val message: Map<String, String> = emptyMap(),
    val imageUrl: String? = null,
    val buttonText: Map<String, String> = emptyMap(),
    val target: String? = null,
    /** 只控制业务主动展示，手动点击仍由入口和业务准入决定。 */
    val autoShow: Boolean = true,
)

@Serializable
data class LivePluginConfig(
    val id: String,
    val enabled: Boolean = true,
    val entry: LiveEntryConfig = LiveEntryConfig(),
    val surface: LiveSurfaceConfig = LiveSurfaceConfig(),
)

/** 预留 API 字段；未收到配置时不产生额外网络请求。 */
@Serializable
data class LiveRoomConfiguration(val plugins: List<LivePluginConfig>? = null)

/** 插件支持范围由客户端声明，配置不能扩展本地能力或授予业务权限。 */
data class LivePluginDefinition(
    val id: String,
    val slot: LiveSlot,
    val order: Int,
    val target: String,
    val presentation: LivePresentation,
    val presentations: Set<LivePresentation> = setOf(presentation),
    val badge: LiveBadgeStyle = LiveBadgeStyle.NONE,
    val visible: Boolean = true,
)

data class LivePluginPresentation(
    val id: String,
    val enabled: Boolean,
    val entry: LiveEntryConfig,
    val surface: LiveSurfaceConfig,
)

/** 每次由完整快照计算；不积累历史 patch，也不让未知 ID 创建新能力。 */
fun resolveLivePlugins(
    definitions: List<LivePluginDefinition>,
    configuration: LiveRoomConfiguration?,
): List<LivePluginPresentation> {
    val configured = configuration?.plugins?.associateBy { it.id }
    return definitions.map { definition ->
        val override = configured?.get(definition.id)
        val entry = override?.entry ?: LiveEntryConfig()
        val surface = override?.surface ?: LiveSurfaceConfig()
        LivePluginPresentation(
            definition.id,
            enabled = override?.enabled ?: true,
            entry = entry.copy(
                visible = entry.visible && (configured?.containsKey(definition.id) ?: definition.visible),
                slot = entry.slot ?: definition.slot,
                order = entry.order ?: definition.order,
                target = entry.target?.takeIf { it.isNotBlank() } ?: definition.target,
                iconUrl = entry.iconUrl.imageUrlOrNull(),
                badge = entry.badge ?: definition.badge,
            ),
            surface = surface.copy(
                presentation = surface.presentation?.takeIf { it in definition.presentations } ?: definition.presentation,
                imageUrl = surface.imageUrl.imageUrlOrNull(),
            ),
        )
    }.sortedBy { it.entry.order }
}

/** 不改变宿主 HTTP 支持；图片不允许 file/javascript 等本地或可执行来源。 */
private fun String?.imageUrlOrNull(): String? = this?.trim()?.takeIf {
    (it.startsWith("https://") || it.startsWith("http://")) && it.substringAfter("://").isNotBlank()
}

fun Map<String, String>.liveText(language: String, fallback: String): String =
    (this[language] ?: this[language.replace('_', '-')] ?: this[language.replace('_', '-').substringBefore('-')])?.takeIf { it.isNotBlank() } ?: fallback

/** 单个插件坏数据局部回退；整体形状错误回默认，合法空列表明确清空可选入口。 */
fun parseLiveRoomConfiguration(value: JsonElement?): LiveRoomConfiguration? {
    val objectValue = value as? JsonObject ?: return null
    val plugins = objectValue["plugins"] ?: return LiveRoomConfiguration()
    if (plugins == JsonNull) return LiveRoomConfiguration()
    if (plugins !is JsonArray) return null
    val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    val parsed = plugins.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val id = (item["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val entry = try { item["entry"]?.let { json.decodeFromJsonElement<LiveEntryConfig>(it) } }
            catch (_: IllegalArgumentException) { null }
        val surface = try { item["surface"]?.let { json.decodeFromJsonElement<LiveSurfaceConfig>(it) } }
            catch (_: IllegalArgumentException) { null }
        LivePluginConfig(id, (item["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
            entry ?: LiveEntryConfig(), surface ?: LiveSurfaceConfig())
    }
    return if (plugins.isNotEmpty() && parsed.isEmpty()) null else LiveRoomConfiguration(parsed)
}

/** 后端接入时 API 与 IM 共用此房间快照；revision 只在同一房间范围内比较。 */
data class LiveRoomConfigurationSnapshot(
    val roomId: String,
    val revision: Long,
    val configuration: LiveRoomConfiguration,
)

/** 一次进房一个 owner；旧房间/旧版本不能覆盖当前展示，不持久化活动配置。 */
class LiveRoomConfigurationState(
    private val roomId: String,
    private val definitions: List<LivePluginDefinition>,
) {
    private var global: LiveRoomConfiguration? = null
    private var room: LiveRoomConfigurationSnapshot? = null
    val plugins: List<LivePluginPresentation>
        get() = resolveLivePlugins(definitions, room?.configuration?.takeIf { it.plugins != null } ?: global)

    fun updateGlobal(configuration: LiveRoomConfiguration?) { global = configuration }

    fun updateRoom(snapshot: LiveRoomConfigurationSnapshot): Boolean {
        if (snapshot.roomId != roomId || snapshot.revision < 0 || snapshot.revision <= (room?.revision ?: -1)) return false
        room = snapshot
        return true
    }
}
