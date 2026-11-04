package com.dgtang.live.room

import kotlinx.serialization.json.Json
import kotlin.test.*

class LiveRoomConfigurationTest {
    private val definitions = listOf(LivePluginDefinition("survey", LiveSlot.SIDE, 20, "live://survey", LivePresentation.PAGE))
    private fun resolve(raw: String) = resolveLivePlugins(definitions, parseLiveRoomConfiguration(Json.parseToJsonElement(raw))).single()

    @Test fun absentAndMalformedConfigurationPreserveDefaultsButEmptyEntriesHideOnlyEntries() {
        assertTrue(resolve("{}").entry.visible)
        assertTrue(resolve("""{"plugins":false}""").entry.visible)
        assertTrue(resolve("""{"plugins":[null, 1]}""").entry.visible)
        val empty = resolve("""{"plugins":[]}""")
        assertFalse(empty.entry.visible)
        assertTrue(empty.enabled)
    }

    @Test fun configurationCannotIntroduceUnknownPluginsOrUnsupportedPresentation() {
        val result = resolve("""{"plugins":[{"id":"unknown"},{"id":"survey","surface":{"presentation":"CENTER"},"entry":{"iconUrl":"javascript:alert(1)","badge":"COUNT"}}]}""")
        assertEquals(LivePresentation.PAGE, result.surface.presentation)
        assertNull(result.entry.iconUrl)
        assertEquals(LiveBadgeStyle.COUNT, result.entry.badge)
        assertEquals(1, resolveLivePlugins(definitions, LiveRoomConfiguration(listOf(LivePluginConfig("unknown")))).size)
    }

    @Test fun disabledAndHiddenAreIndependentAndHttpImagesRemainSupported() {
        val result = resolve("""{"plugins":[{"id":"survey","enabled":false,"entry":{"visible":true,"iconUrl":"http://example.com/a.png"}}]}""")
        assertFalse(result.enabled)
        assertTrue(result.entry.visible)
        assertEquals("http://example.com/a.png", result.entry.iconUrl)
    }

    @Test fun invalidPluginContentFallsBackWithoutDroppingOtherEntries() {
        val result = resolve("""{"plugins":[{"id":"survey","entry":3}]}""")
        assertEquals("live://survey", result.entry.target)
        assertTrue(result.entry.visible)
    }

    @Test fun roomSnapshotRejectsOtherRoomsAndOldVersionsWhileGlobalUpdatesRemainIndependent() {
        val owner = LiveRoomConfigurationState("9", definitions)
        val disabled = LiveRoomConfiguration(listOf(LivePluginConfig("survey", enabled = false)))
        assertFalse(owner.updateRoom(LiveRoomConfigurationSnapshot("10", 100, disabled)))
        assertTrue(owner.updateRoom(LiveRoomConfigurationSnapshot("9", 2, disabled)))
        owner.updateGlobal(LiveRoomConfiguration())
        assertFalse(owner.plugins.single().enabled)
        assertFalse(owner.updateRoom(LiveRoomConfigurationSnapshot("9", 1, LiveRoomConfiguration())))
        assertTrue(owner.updateRoom(LiveRoomConfigurationSnapshot("9", 3, LiveRoomConfiguration())))
        assertTrue(owner.plugins.single().enabled)
    }

    @Test fun malformedEntryCannotUndoExplicitBusinessDisable() {
        assertFalse(resolve("""{"plugins":[{"id":"survey","enabled":false,"entry":3}]}""").enabled)
    }

    @Test fun translatedTextUsesLanguageFallbackAndDoesNotReplaceMissingLocalText() {
        assertEquals("问卷", mapOf("zh" to "问卷").liveText("zh-CN", "Survey"))
        assertEquals("问卷", mapOf("zh" to "问卷").liveText("zh_CN", "Survey"))
        assertEquals("Survey", mapOf("en" to " ").liveText("en", "Survey"))
    }
}
