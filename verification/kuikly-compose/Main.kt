@file:OptIn(androidx.compose.runtime.ExperimentalComposeApi::class)
package io.github.gycrosskit.livesdk.verification

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import io.github.gycrosskit.livesdk.*
import io.github.gycrosskit.livesdk.kuikly.LiveVideo
import kotlin.coroutines.EmptyCoroutineContext

private class EmptyApplier : AbstractApplier<Unit>(Unit) {
    override fun insertBottomUp(index: Int, instance: Unit) {}
    override fun insertTopDown(index: Int, instance: Unit) {}
    override fun move(from: Int, to: Int, count: Int) {}
    override fun remove(index: Int, count: Int) {}
    override fun onClear() {}
}

fun main() {
    val recomposer = Recomposer(EmptyCoroutineContext)
    val composition = ControlledComposition(EmptyApplier(), recomposer)
    var room by mutableStateOf("A")
    var active by mutableStateOf(true)
    var shown by mutableStateOf(true)
    var extras by mutableStateOf<Map<String, String>>(linkedMapOf("businessRoomId" to "11", "owner" to "first"))
    val reports = mutableListOf<String>()
    val firstReporter = LiveLikeReporter { live, user, count -> reports += "first:$live:$user:$count" }
    val secondReporter = LiveLikeReporter { live, user, count -> reports += "second:$live:$user:$count" }
    var reporter by mutableStateOf(firstReporter)
    var eventTarget by mutableStateOf(1)
    val events = mutableListOf<Int>()
    val controllers = mutableListOf<KuiklyLiveView?>()
    val controller: (KuiklyLiveView?) -> Unit = { controllers += it }
    val content: @Composable () -> Unit = {
        if (shown) {
            val capturedTarget = eventTarget
            LiveVideo(room, active = active, likeReporter = reporter, roomExtras = extras,
                onEvent = { events += capturedTarget }, onController = controller)
        }
    }
    fun recompose() {
        Snapshot.sendApplyNotifications()
        composition.invalidateAll()
        if (composition.recompose()) {
            composition.applyChanges()
            composition.applyLateChanges()
            composition.changesApplied()
        }
    }
    var passed = 0
    fun scenario(name: String, check: () -> Unit) { check(); passed++; println("PASS $name") }
    try {
        composition.composeContent(content); composition.applyChanges()
        val first = controllers.filterNotNull().single()
        scenario("reporter is injected before first room submission and extras preserve host identity") {
            check(first.reporterAtSubmission.single() === firstReporter)
            check(first.rooms.single().optString("businessRoomId") == "11")
            check(first.rooms.single().optString("liveId") == "A")
        }
        scenario("equal room and reordered equal extras never reopen an explicitly released room") {
            first.release()
            extras = linkedMapOf("owner" to "first", "businessRoomId" to "11"); recompose()
            check(first.rooms.size == 1)
        }
        scenario("latest callback is used without recreating or releasing the node") {
            eventTarget = 2; recompose()
            first.listener?.invoke(JSONObject())
            check(events == listOf(2)); check(controllers.filterNotNull().size == 1)
            check(first.releaseCount == 1)
        }
        scenario("active and room changes update once while keeping the same node") {
            active = false; recompose(); check(!first.rooms.last().optBoolean("active"))
            recompose(); check(first.rooms.size == 2)
            room = "B"; recompose(); check(first.rooms.last().optString("liveId") == "B")
            check(first.rooms.size == 3); check(controllers.filterNotNull().size == 1)
        }
        scenario("host extras cannot override canonical playback parameters") {
            extras = mapOf("liveId" to "foreign", "active" to "true", "preview" to "true", "businessRoomId" to "22")
            recompose()
            check(first.rooms.last().optString("liveId") == "B")
            check(!first.rooms.last().optBoolean("active")); check(!first.rooms.last().optBoolean("preview"))
            check(first.rooms.last().optString("businessRoomId") == "22")
        }
        scenario("reporter remains bound to its node when caller recomposes") {
            reporter = secondReporter; recompose()
            check(first.likeReporter === firstReporter)
            first.likeReporter.report("A", "old-user", 3)
            check(reports == listOf("first:A:old-user:3"))
        }
        scenario("dispose ignores late events and a new node captures the new reporter") {
            shown = false; recompose()
            check(first.releaseCount == 2); check(controllers.last() == null)
            first.listener?.invoke(JSONObject()); check(events == listOf(2))
            shown = true; recompose()
            val second = controllers.filterNotNull().last()
            check(second !== first); check(second.likeReporter === secondReporter)
            check(second.reporterAtSubmission.single() === secondReporter)
            composition.dispose(); check(second.releaseCount == 1)
        }
        println("RESULT $passed scenarios, 0 failed")
    } finally { if (!composition.isDisposed) composition.dispose(); recomposer.close() }
}
