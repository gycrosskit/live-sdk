package io.github.gycrosskit.livesdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

class KuiklyLiveProtocolTest {
    @Test fun roomConfigurationIsAtomicAndRejectsBlankId() {
        assertEquals(KuiklyLiveRoom("room", true, false), KuiklyLiveRoom.parse("{\"liveId\":\"room\",\"preview\":true,\"active\":false}"))
        assertFails { KuiklyLiveRoom.parse("{\"liveId\":\" \"}") }
    }

    @Test fun snapshotKeepsTheExistingMessageAndHostContract() {
        val snapshot = LiveAudienceContentSnapshot.Empty.copy(
            messages = listOf(LiveAudienceMessageSnapshot(7L, 3.5, "u", "name", "avatar", "a\"b", "biz", "payload", LiveAudienceMessageKind.MEMBER_LEFT)),
            host = LiveAudienceContentSnapshot.Empty.host.copy(roomId = "room", followed = true),
        )
        val json = JSONObject(snapshot.toKuiklyJson())
        assertTrue(json.optJSONObject("host")!!.optBoolean("followed"))
        assertEquals("a\"b", json.optJSONArray("messages")!!.optJSONObject(0)!!.optString("content"))
        assertEquals("MEMBER_LEFT", json.optJSONArray("messages")!!.optJSONObject(0)!!.optString("kind"))
    }
}
