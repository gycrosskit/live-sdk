package io.github.gycrosskit.livesdk

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KuiklyLiveEventDecodingTest {
    @Test
    fun snapshotEncodingRoundTripPreservesEveryFieldAndMessageKind() {
        val user = LiveAudienceUserSnapshot("owner", "", "avatar")
        val snapshot = LiveAudienceContentSnapshot(
            messages = LiveAudienceMessageKind.entries.mapIndexed { index, kind ->
                LiveAudienceMessageSnapshot(
                    sequence = index.toLong() - 1,
                    timestampSeconds = 123.5,
                    senderId = "sender",
                    senderName = "Sender",
                    senderAvatarUrl = "sender-avatar",
                    content = "raw \"text\"\n你好",
                    businessId = "business",
                    data = "{\"amount\":\"$1\",\"emoji\":\"😮‍💨\"}",
                    kind = kind,
                )
            },
            introduction = "notice",
            interactionReady = true,
            loading = false,
            pictureInPicture = true,
            likeEffectSequence = 42L,
            host = LiveAudienceHostSnapshot("room", "Room", user, true, true, true, 99L),
            audience = listOf(user, LiveAudienceUserSnapshot("viewer", "Viewer", "")),
            audienceCount = 5,
        )
        val event = JSONObject().put("type", "snapshot").put("snapshot", JSONObject(snapshot.toKuiklyJson()))

        assertEquals(snapshot, event.toLiveAudienceSnapshot())
        assertFalse(event.dispatchLivePlaybackEvent(LivePlaybackCallbacks()))
    }

    @Test
    fun snapshotRequiresAnObjectEnvelopeAndKeepsEmptyDefaults() {
        for (json in listOf("{}", """{"type":"unknown","snapshot":{}}""", """{"type":"snapshot"}""",
            """{"type":"snapshot","snapshot":false}""", """{"type":"snapshot","snapshot":[]}""")) {
            assertNull(JSONObject(json).toLiveAudienceSnapshot())
        }
        val empty = JSONObject("""{"type":"snapshot","snapshot":{}}""").toLiveAudienceSnapshot()
        assertEquals(LiveAudienceContentSnapshot.Empty, empty)
    }

    @Test
    fun malformedContainersAndArrayItemsAreIgnoredWithoutDroppingValidItems() {
        val event = JSONObject("""{"type":"snapshot","snapshot":{"host":{"owner":false},"messages":[false,4,"bad",[],{"kind":"FUTURE_KIND","content":"original","businessId":"business","data":"raw"}],"audience":[false,[],"bad",{"id":"viewer","name":"","avatarUrl":"avatar"}]}}""")
        val snapshot = assertNotNull(event.toLiveAudienceSnapshot())
        assertNull(snapshot.host.owner)
        val message = snapshot.messages.single()
        assertEquals(LiveAudienceMessageKind.TEXT, message.kind)
        assertEquals("original", message.content)
        assertEquals("business", message.businessId)
        assertEquals("raw", message.data)
        assertEquals(listOf(LiveAudienceUserSnapshot("viewer", "", "avatar")), snapshot.audience)
        for (host in listOf("false", "[]", "4", "\"bad\"")) {
            assertEquals(LiveAudienceContentSnapshot.Empty,
                JSONObject("""{"type":"snapshot","snapshot":{"host":$host,"messages":false,"audience":false}}""")
                    .toLiveAudienceSnapshot())
        }
    }

    @Test
    fun encodedPlaybackCallbacksDispatchOnceWithTheirOriginalArguments() {
        val calls = mutableListOf<String>()
        val callbacks = LivePlaybackCallbacks(
            onJoinStarted = { calls += "joinStarted" },
            onJoinSucceeded = { calls += "joinSucceeded" },
            onJoinFailed = { code, message -> calls += "joinFailed:$code:$message" },
            onLiveUnavailable = { calls += "unavailable:$it" },
            onKickedOut = { calls += "kickedOut" },
            onLiveEnded = { calls += "liveEnded" },
            onCurrentUserMessageDisabled = { calls += "disabled:$it" },
            onPictureInPictureChanged = { calls += "pip:$it" },
        )
        val encoded = kuiklyLiveCallbacks { json -> assertTrue(JSONObject(json).dispatchLivePlaybackEvent(callbacks)) }
        encoded.onJoinStarted()
        encoded.onJoinSucceeded()
        encoded.onJoinFailed(42, "failure")
        encoded.onLiveUnavailable("unavailable")
        encoded.onKickedOut()
        encoded.onLiveEnded()
        encoded.onCurrentUserMessageDisabled(true)
        encoded.onPictureInPictureChanged(true)

        assertEquals(listOf("joinStarted", "joinSucceeded", "joinFailed:42:failure", "unavailable:unavailable",
            "kickedOut", "liveEnded", "disabled:true", "pip:true"), calls)
    }

    @Test
    fun unknownPreviewAndSnapshotEventsDoNotInvokePlaybackCallbacks() {
        val callbacks = LivePlaybackCallbacks(
            onJoinStarted = { error("Unexpected join start") },
            onJoinSucceeded = { error("Unexpected join success") },
            onJoinFailed = { _, _ -> error("Unexpected join failure") },
            onLiveUnavailable = { error("Unexpected unavailable") },
            onKickedOut = { error("Unexpected kick") },
            onLiveEnded = { error("Unexpected end") },
            onCurrentUserMessageDisabled = { error("Unexpected mute") },
            onPictureInPictureChanged = { error("Unexpected PiP") },
        )
        for (type in listOf("", "futureEvent", "previewState", "snapshot")) {
            assertFalse(JSONObject().put("type", type).dispatchLivePlaybackEvent(callbacks) { _, _ -> error("Unexpected error") })
        }
    }

    @Test
    fun playbackErrorIsIndependentFromRoomTerminalsAndDefaultsToMinusOne() {
        val errors = mutableListOf<Pair<Int, String>>()
        val terminals = mutableListOf<String>()
        val callbacks = LivePlaybackCallbacks(
            onJoinFailed = { code, message -> terminals += "joinFailed:$code:$message" },
            onLiveUnavailable = { terminals += "unavailable:$it" },
            onKickedOut = { terminals += "kickedOut" },
            onLiveEnded = { terminals += "liveEnded" },
        )
        val onError: (Int, String) -> Unit = { code, message -> errors += code to message }
        assertTrue(JSONObject("""{"type":"playbackError","code":-2301,"message":"raw vendor detail"}""")
            .dispatchLivePlaybackEvent(callbacks, onError))
        assertTrue(JSONObject().put("type", "playbackError").dispatchLivePlaybackEvent(callbacks, onError))
        assertEquals(listOf(-2301 to "raw vendor detail", -1 to ""), errors)
        assertTrue(terminals.isEmpty())

        for (type in listOf("joinFailed", "liveUnavailable", "kickedOut", "liveEnded")) {
            assertTrue(JSONObject().put("type", type).dispatchLivePlaybackEvent(callbacks, onError))
        }
        assertEquals(listOf("joinFailed:0:", "unavailable:", "kickedOut", "liveEnded"), terminals)
        assertEquals(2, errors.size)
        assertTrue(JSONObject().put("type", "playbackError").dispatchLivePlaybackEvent(callbacks))
    }

    @Test
    fun callbackFailuresPropagateWithoutRetryOrAnotherEvent() {
        val failure = IllegalStateException("callback failed")
        var calls = 0
        val callbacks = LivePlaybackCallbacks(onJoinSucceeded = { calls++; throw failure })
        assertSame(failure, assertFailsWith<IllegalStateException> {
            JSONObject().put("type", "joinSucceeded").dispatchLivePlaybackEvent(callbacks)
        })
        assertEquals(1, calls)

        assertSame(failure, assertFailsWith<IllegalStateException> {
            JSONObject().put("type", "playbackError").dispatchLivePlaybackEvent(LivePlaybackCallbacks()) { _, _ ->
                calls++; throw failure
            }
        })
        assertEquals(2, calls)
    }
}
