package com.meetroute.app.navigation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val MEETING = NavigationTarget("평창(강릉)", 37.6055, 128.4526, placeId = "50-평창(강릉)")
private val NEXT = NavigationTarget("횡성(강릉)", 37.46, 128.13, placeId = "50-횡성(강릉)")
private val FINAL = NavigationTarget("강릉역", 37.7640, 128.8996)
private val BUSAN = NavigationTarget("부산역", 35.1152, 129.0422)

private fun waypoint(target: NavigationTarget, seconds: Int = 600, meters: Int = 9_000) =
    WaypointSnapshot(target.name, seconds, meters, target.lat, target.lon)

private val DRIVER_STOP = waypoint(NavigationTarget("운전자 주유소", 37.3, 127.5))
private val OTHER_STOP = waypoint(NavigationTarget("운전자 식당", 37.4, 127.7))

/** 실제 내비처럼 요청을 받으면 상태가 바뀌는 대역. 조회에는 지금 상태를 답한다. */
private class FakeNavi(
    private val connects: Boolean = true,
    var routing: Boolean = false,
    var destination: NavigationTarget? = null,
    waypoints: List<WaypointSnapshot> = emptyList(),
    private val throwOnApply: Boolean = false,
) : NaviHelperPort {
    lateinit var events: NaviHelperEvents
    val waypoints = waypoints.toMutableList()
    val calls = mutableListOf<String>()
    var released = false
    var answersRouteState = true
    var answersWaypoints = true
    var answersDestination = true

    /** false면 요청을 받아 두기만 한다. [finish]를 부를 때 반영된다. */
    var appliesImmediately = true
    var ignoresRequests = false
    var failsRemoval = false

    /** 실제 내비처럼 경유지를 바꿀 때 경로를 취소했다가 [finish]에서 다시 만든다. 그 사이 조회는 빈 값이다. */
    var rebuildsRoutes = false

    /** 실제 내비처럼 onRoutingCompleted 없이 onRouteEnded로 탐색을 끝낸다. */
    var noCompletionCallback = false

    /** 실제 내비처럼 경유지 조회에 좌표를 0.0으로 답한다. */
    var hidesWaypointPositions = false
    private var rebuilding = false
    private var pending: (() -> Unit)? = null

    fun finish() {
        val apply = pending ?: return
        pending = null
        if (rebuilding) {
            rebuilding = false
            routing = true
            apply()
            events.onRouteEnded()
            events.onGuidanceChanged(true)
        } else {
            apply()
        }
    }

    override fun initialize(events: NaviHelperEvents, onConnected: () -> Unit, onFailed: (Throwable) -> Unit) {
        this.events = events
        if (connects) onConnected() else onFailed(IllegalStateException("서비스 없음"))
    }

    override fun requestRouteState() {
        if (answersRouteState) events.onRouteState(routing)
    }

    override fun requestWaypoints() {
        if (!answersWaypoints) return
        val reply = if (rebuilding) emptyList() else waypoints.toList()
        events.onWaypoints(if (hidesWaypointPositions) reply.map { it.copy(lat = 0.0, lon = 0.0) } else reply)
    }

    override fun requestDestination() {
        val current = destination.takeUnless { rebuilding } ?: NavigationTarget("", 0.0, 0.0)
        if (answersDestination) events.onDestination(DestinationSnapshot(current, 3600, 100_000))
    }

    override fun requestCurrentLocation() = events.onCurrentLocation(VehiclePosition(37.5, 127.0, 90.0))

    override fun addWaypoint(target: NavigationTarget, slot: WaypointSlot) = record("addWaypoint ${target.name} $slot") {
        waypoints += waypoint(target, 1800, 18_000)
        events.onWaypointChanged()
    }

    override fun removeWaypoint(slot: WaypointSlot) = record("removeWaypoint $slot") {
        if (!failsRemoval) waypoints.removeAt(slot.ordinal)
        events.onWaypointChanged()
    }

    override fun requestRoute(destination: NavigationTarget) = record("requestRoute ${destination.name}") {
        route(destination, emptyList())
    }

    override fun requestRouteWithWaypoints(destination: NavigationTarget, waypoints: List<NavigationTarget>) =
        record("requestRouteWithWaypoints ${destination.name} ${waypoints.map { it.name }}") {
            route(destination, waypoints)
        }

    private fun route(target: NavigationTarget, stops: List<NavigationTarget>) {
        routing = true
        destination = target
        waypoints.clear()
        stops.forEach { waypoints += waypoint(it, 1800, 18_000) }
        events.onRouteStarted()
        if (noCompletionCallback) events.onRouteEnded() else events.onRoutingCompleted()
    }

    override fun requestReRoute() = record("requestReRoute") { events.onReRouteEnded() }

    override fun openNavigationScreen() = record("openNavigationScreen") {}

    override fun openPlaceSearch(keyword: String) = record("openPlaceSearch $keyword") {}

    override fun release() {
        released = true
    }

    private fun record(call: String, apply: () -> Unit) {
        if (throwOnApply) throw IllegalStateException("SDK 호출 실패")
        calls += call
        when {
            ignoresRequests -> Unit
            rebuildsRoutes -> {
                pending = apply
                rebuilding = true
                routing = false
                events.onRouteCancelled()
                events.onGuidanceChanged(false)
            }
            appliesImmediately -> apply()
            else -> pending = apply
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PleosNaviHelperGatewayTest {

    private fun gateway(port: FakeNavi, store: AppliedPointStore = InMemoryAppliedPointStore()) =
        PleosNaviHelperGateway(portFactory = { port }, appliedPoints = store)

    // ---- 성공 판단 ----

    @Test
    fun `request alone is not treated as success`() = runTest {
        val port = FakeNavi().apply { appliesImmediately = false }
        val gateway = gateway(port)
        val result = async { gateway.applyMeetingPoint(MEETING, FINAL) }
        runCurrent()
        assertFalse(result.isCompleted)

        port.finish()
        assertEquals(NavigationResult.Applied, result.await())
    }

    @Test
    fun `no response ends in timeout not success`() = runTest {
        val port = FakeNavi().apply { ignoresRequests = true }
        val result = async { gateway(port).applyMeetingPoint(MEETING, FINAL) }
        advanceTimeBy(25_000)
        assertEquals(NavigationResult.TimedOut, result.await())
    }

    @Test
    fun `route search start is not completion`() = runTest {
        val port = FakeNavi().apply { ignoresRequests = true }
        val gateway = gateway(port)
        val result = async { gateway.applyDestination(FINAL) }
        runCurrent()
        // 내비가 목적지를 먼저 바꾼 뒤 탐색에 실패하는 경우
        port.destination = FINAL
        port.events.onRouteStarted()
        runCurrent()
        assertFalse(result.isCompleted)

        port.events.onError("CAUSE_SEARCH_ROUTE")
        assertEquals(NavigationResult.Failed("경로 탐색 서버 오류입니다."), result.await())
    }

    @Test
    fun `missing completion callback is covered by state after grace period`() = runTest {
        val port = FakeNavi().apply { ignoresRequests = true }
        val gateway = gateway(port)
        val result = async { gateway.applyDestination(FINAL) }
        runCurrent()
        port.destination = FINAL
        port.events.onRouteStarted()
        assertEquals(NavigationResult.Applied, result.await())
    }

    @Test
    fun `unrelated reroute does not complete a pending waypoint request`() = runTest {
        val port = FakeNavi(routing = true, destination = FINAL).apply { ignoresRequests = true }
        val gateway = gateway(port)
        val result = async { gateway.applyMeetingPoint(MEETING, FINAL) }
        runCurrent()
        port.events.onReRouteEnded()
        port.events.onWaypointChanged()
        runCurrent()
        assertFalse(result.isCompleted)
        result.cancel()
    }

    @Test
    fun `completed search without the waypoint is reported as unconfirmed`() = runTest {
        val port = FakeNavi().apply { ignoresRequests = true }
        val gateway = gateway(port)
        val result = async { gateway.applyMeetingPoint(MEETING, FINAL) }
        runCurrent()
        port.destination = FINAL
        port.events.onRoutingCompleted()
        assertEquals(NavigationResult.Unconfirmed, result.await())
    }

    @Test
    fun `navi error is reported with readable reason`() = runTest {
        val port = FakeNavi().apply { ignoresRequests = true }
        val gateway = gateway(port)
        val result = async { gateway.applyMeetingPoint(MEETING, FINAL) }
        runCurrent()
        port.events.onError("CAUSE_SEARCH_ROUTE_OVER_DISTANCE")
        assertEquals(NavigationResult.Failed("경로 탐색 가능 거리를 벗어났습니다."), result.await())
    }

    @Test
    fun `unknown navi error keeps its code`() = runTest {
        val port = FakeNavi().apply { ignoresRequests = true }
        val gateway = gateway(port)
        val result = async { gateway.applyDestination(FINAL) }
        runCurrent()
        port.events.onError("CAUSE_NEW")
        assertEquals(NavigationResult.Failed("내비게이션 오류: CAUSE_NEW"), result.await())
    }

    @Test
    fun `sdk exception becomes failure`() = runTest {
        val result = gateway(FakeNavi(throwOnApply = true)).applyMeetingPoint(MEETING, FINAL)
        assertTrue(result is NavigationResult.Failed)
    }

    @Test
    fun `reroute is only requested because navi sends no callback`() = runTest {
        // 실측: 경로를 벗어나지 않으면 내비는 재탐색 요청에 콜백을 보내지 않는다. 기다리면 1분 동안 버튼이 잠긴다.
        val port = FakeNavi(routing = true, destination = FINAL).apply { ignoresRequests = true }
        val result = async { gateway(port).reroute() }
        runCurrent()
        assertTrue(result.isCompleted)
        assertEquals(NavigationResult.Unconfirmed, result.await())
        assertEquals(listOf("requestReRoute"), port.calls)
    }

    @Test
    fun `reroute request failure is reported`() = runTest {
        val gateway = gateway(FakeNavi(throwOnApply = true))
        gateway.connect()
        assertTrue(gateway.reroute() is NavigationResult.Failed)
    }

    // ---- 조회 실패 ----

    @Test
    fun `unknown route state does not replace the route`() = runTest {
        val port = FakeNavi().apply { answersRouteState = false }
        val result = gateway(port).applyMeetingPoint(MEETING, FINAL)
        assertTrue(result is NavigationResult.Failed)
        assertTrue(port.calls.isEmpty())
    }

    @Test
    fun `unknown waypoints do not count as empty slots`() = runTest {
        val port = FakeNavi(routing = true, destination = FINAL).apply { answersWaypoints = false }
        val result = gateway(port).applyMeetingPoint(MEETING, FINAL)
        assertTrue(result is NavigationResult.Failed)
        assertTrue(port.calls.isEmpty())
    }

    @Test
    fun `unknown destination does not count as the group destination`() = runTest {
        val port = FakeNavi(routing = true, destination = FINAL).apply { answersDestination = false }
        val result = gateway(port).applyMeetingPoint(MEETING, FINAL)
        assertTrue(result is NavigationResult.Failed)
        assertTrue(port.calls.isEmpty())
    }

    // ---- 경로 적용 ----

    @Test
    fun `without active route destination and waypoint are requested together`() = runTest {
        val port = FakeNavi()
        assertEquals(NavigationResult.Applied, gateway(port).applyMeetingPoint(MEETING, FINAL))
        assertEquals(listOf("requestRouteWithWaypoints 강릉역 [평창(강릉)]"), port.calls)
    }

    @Test
    fun `during guidance waypoint is added to first free slot`() = runTest {
        val port = FakeNavi(routing = true, destination = FINAL)
        assertEquals(NavigationResult.Applied, gateway(port).applyMeetingPoint(MEETING, FINAL))
        assertEquals(listOf("addWaypoint 평창(강릉) FIRST"), port.calls)
    }

    @Test
    fun `driver waypoint is kept and meeting point uses second slot`() = runTest {
        val port = FakeNavi(routing = true, destination = FINAL, waypoints = listOf(DRIVER_STOP))
        assertEquals(NavigationResult.Applied, gateway(port).applyMeetingPoint(MEETING, FINAL))
        assertEquals(listOf("addWaypoint 평창(강릉) SECOND"), port.calls)
    }

    @Test
    fun `full waypoint slots are reported without removing driver waypoints`() = runTest {
        val port = FakeNavi(routing = true, destination = FINAL, waypoints = listOf(DRIVER_STOP, OTHER_STOP))
        val result = gateway(port).applyMeetingPoint(MEETING, FINAL)
        assertTrue(result is NavigationResult.Failed)
        assertTrue(port.calls.isEmpty())
    }

    @Test
    fun `applying the same meeting point again changes nothing`() = runTest {
        val port = FakeNavi(routing = true, destination = FINAL)
        val gateway = gateway(port)
        gateway.applyMeetingPoint(MEETING, FINAL)
        assertEquals(NavigationResult.Applied, gateway.applyMeetingPoint(MEETING, FINAL))
        assertEquals(listOf("addWaypoint 평창(강릉) FIRST"), port.calls)
    }

    @Test
    fun `replanning removes only the previous meeting waypoint`() = runTest {
        val port = FakeNavi(routing = true, destination = FINAL, waypoints = listOf(DRIVER_STOP))
        val gateway = gateway(port)
        gateway.applyMeetingPoint(MEETING, FINAL)
        assertEquals(NavigationResult.Applied, gateway.applyMeetingPoint(NEXT, FINAL))
        assertEquals(
            listOf("addWaypoint 평창(강릉) SECOND", "removeWaypoint SECOND", "addWaypoint 횡성(강릉) SECOND"),
            port.calls,
        )
        assertEquals(listOf("운전자 주유소", "횡성(강릉)"), port.waypoints.map { it.name })
    }

    @Test
    fun `previous meeting point is found by position after waypoint numbers shift`() = runTest {
        val port = FakeNavi(routing = true, destination = FINAL, waypoints = listOf(DRIVER_STOP))
        val gateway = gateway(port)
        gateway.applyMeetingPoint(MEETING, FINAL)
        port.waypoints.removeAt(0) // 운전자 경유지를 지나 합류 지점이 첫 번째가 됐다.
        gateway.applyMeetingPoint(NEXT, FINAL)
        assertEquals(listOf("횡성(강릉)"), port.waypoints.map { it.name })
    }

    @Test
    fun `driver waypoint added after a new route is not removed as old meeting point`() = runTest {
        val port = FakeNavi()
        val gateway = gateway(port)
        gateway.applyMeetingPoint(MEETING, FINAL)
        gateway.applyDestination(FINAL) // 합류 후 목적지를 다시 잡아 경유지가 없어졌다.
        port.waypoints.clear()
        port.waypoints += DRIVER_STOP
        gateway.applyMeetingPoint(NEXT, FINAL)
        assertEquals(listOf("운전자 주유소", "횡성(강릉)"), port.waypoints.map { it.name })
    }

    @Test
    fun `old meeting point is removed after the app restarts`() = runTest {
        val store = InMemoryAppliedPointStore()
        val port = FakeNavi(routing = true, destination = FINAL)
        gateway(port, store).applyMeetingPoint(MEETING, FINAL)

        val restarted = gateway(port, store)
        restarted.applyMeetingPoint(NEXT, FINAL)
        assertEquals(listOf("횡성(강릉)"), port.waypoints.map { it.name })
    }

    @Test
    fun `failed removal stops before adding the new meeting point`() = runTest {
        val port = FakeNavi(routing = true, destination = FINAL)
        val gateway = gateway(port)
        gateway.applyMeetingPoint(MEETING, FINAL)
        port.failsRemoval = true
        val result = gateway.applyMeetingPoint(NEXT, FINAL)
        assertTrue(result is NavigationResult.Failed)
        assertFalse(port.calls.any { it.startsWith("addWaypoint 횡성") })
    }

    @Test
    fun `meeting at destination removes the old meeting waypoint and keeps guidance`() = runTest {
        val port = FakeNavi(routing = true, destination = FINAL, waypoints = listOf(DRIVER_STOP))
        val gateway = gateway(port)
        gateway.applyMeetingPoint(MEETING, FINAL)
        assertEquals(NavigationResult.Applied, gateway.applyDestination(FINAL))
        assertEquals(listOf("운전자 주유소"), port.waypoints.map { it.name })
        assertFalse(port.calls.any { it.startsWith("requestRoute") })
    }

    // ---- 다른 목적지로 안내 중 ----

    @Test
    fun `different destination asks the driver and changes nothing`() = runTest {
        val port = FakeNavi(routing = true, destination = BUSAN)
        val result = gateway(port).applyMeetingPoint(MEETING, FINAL)
        assertEquals(NavigationResult.DestinationMismatch(BUSAN, FINAL), result)
        assertTrue(port.calls.isEmpty())
    }

    @Test
    fun `approved change sets common destination and keeps driver waypoint`() = runTest {
        val port = FakeNavi(routing = true, destination = BUSAN, waypoints = listOf(DRIVER_STOP))
        val result = gateway(port).applyMeetingPoint(MEETING, FINAL, replaceDestination = true)
        assertEquals(NavigationResult.Applied, result)
        assertEquals(FINAL, port.destination)
        assertEquals(listOf("운전자 주유소", "평창(강릉)"), port.waypoints.map { it.name })
    }

    @Test
    fun `approved change fails when driver waypoints leave no room`() = runTest {
        val port = FakeNavi(routing = true, destination = BUSAN, waypoints = listOf(DRIVER_STOP, OTHER_STOP))
        val result = gateway(port).applyMeetingPoint(MEETING, FINAL, replaceDestination = true)
        assertTrue(result is NavigationResult.Failed)
        assertEquals(BUSAN, port.destination)
    }

    @Test
    fun `nearby coordinates of the same destination are not a mismatch`() = runTest {
        val port = FakeNavi(routing = true, destination = FINAL.copy(lat = FINAL.lat + 0.001))
        assertEquals(NavigationResult.Applied, gateway(port).applyMeetingPoint(MEETING, FINAL))
    }

    @Test
    fun `same name a little apart is the same destination`() = runTest {
        // 내비에서 고른 강릉역과 앱의 강릉역은 기준 좌표가 2.5km쯤 다르다.
        val navi = NavigationTarget("강릉역", 37.7519, 128.8761)
        val port = FakeNavi(routing = true, destination = navi)
        assertEquals(NavigationResult.Applied, gateway(port).applyMeetingPoint(MEETING, FINAL))
        assertEquals(listOf("addWaypoint 평창(강릉) FIRST"), port.calls)
    }

    @Test
    fun `same name far away is a different destination`() = runTest {
        val elsewhere = NavigationTarget("강릉역", 37.0, 127.0)
        val port = FakeNavi(routing = true, destination = elsewhere)
        val result = gateway(port).applyMeetingPoint(MEETING, FINAL)
        assertTrue(result is NavigationResult.DestinationMismatch)
    }

    // ---- Pleos 에뮬레이터에서 실측한 동작 ----

    @Test
    fun `slow route calculation is not a timeout`() = runTest {
        val port = FakeNavi(routing = true, destination = FINAL).apply { rebuildsRoutes = true }
        val result = async { gateway(port).applyMeetingPoint(MEETING, FINAL) }
        runCurrent()
        advanceTimeBy(29_000)
        assertFalse(result.isCompleted)

        port.finish()
        assertEquals(NavigationResult.Applied, result.await())
    }

    @Test
    fun `empty waypoint list during rebuild does not confirm the removal`() = runTest {
        val port = FakeNavi(routing = true, destination = FINAL)
        val gateway = gateway(port)
        gateway.applyMeetingPoint(MEETING, FINAL)

        port.rebuildsRoutes = true
        val result = async { gateway.applyMeetingPoint(NEXT, FINAL) }
        advanceTimeBy(10_000)
        assertEquals("다시 만드는 중에 다음 요청을 보내면 안 됩니다", 2, port.calls.size)

        port.finish()
        advanceTimeBy(3_000)
        assertEquals("addWaypoint 횡성(강릉) FIRST", port.calls.last())

        port.finish()
        assertEquals(NavigationResult.Applied, result.await())
        assertEquals(listOf("횡성(강릉)"), port.waypoints.map { it.name })
    }

    @Test
    fun `guidance back before the rebuild finishes does not release the next request`() = runTest {
        val port = FakeNavi(routing = true, destination = FINAL)
        val gateway = gateway(port)
        gateway.applyMeetingPoint(MEETING, FINAL)

        port.rebuildsRoutes = true
        val result = async { gateway.applyMeetingPoint(NEXT, FINAL) }
        runCurrent()
        // 실측: 안내 상태와 빈 경유지 목록이 onWaypointChanged보다 5초 먼저 돌아온다.
        port.routing = true
        port.waypoints.clear()
        advanceTimeBy(5_000)
        assertFalse(port.calls.any { it.startsWith("addWaypoint 횡성") })
        result.cancel()
    }

    @Test
    fun `route end without a completion callback finishes the search at once`() = runTest {
        val port = FakeNavi().apply { noCompletionCallback = true }
        val result = async { gateway(port).applyMeetingPoint(MEETING, FINAL) }
        advanceTimeBy(3_000)
        assertTrue("onRouteEnded 뒤에는 8초를 더 기다리지 않아야 합니다", result.isCompleted)
        assertEquals(NavigationResult.Applied, result.await())
    }

    @Test
    fun `waypoint without coordinates is found by name`() = runTest {
        val port = FakeNavi(routing = true, destination = FINAL).apply { hidesWaypointPositions = true }
        val gateway = gateway(port)
        assertEquals(NavigationResult.Applied, gateway.applyMeetingPoint(MEETING, FINAL))
        gateway.refreshProgress()
        assertEquals(1800, gateway.progress.value?.remainSeconds)
    }

    @Test
    fun `driver waypoint without coordinates is never sent as zero position`() = runTest {
        val unknown = WaypointSnapshot("운전자 주유소", 600, 9_000, 0.0, 0.0)
        val port = FakeNavi(routing = true, destination = BUSAN, waypoints = listOf(unknown))
        val result = gateway(port).applyMeetingPoint(MEETING, FINAL, replaceDestination = true)
        assertEquals(NavigationResult.Applied, result)
        assertEquals(listOf("requestRouteWithWaypoints 강릉역 [평창(강릉)]"), port.calls)
    }

    @Test
    fun `waypoint without coordinates does not match a place at zero position`() {
        val unknown = WaypointSnapshot("이름 다른 곳", 600, 9_000, 0.0, 0.0)
        assertFalse(unknown.matches(NavigationTarget("적도", 0.0, 0.0)))
        assertNull(unknown.toTarget())
    }

    // ---- 진행 상황 ----

    @Test
    fun `progress follows the meeting waypoint not the first waypoint`() = runTest {
        val port = FakeNavi(routing = true, destination = FINAL, waypoints = listOf(DRIVER_STOP))
        val gateway = gateway(port)
        gateway.applyMeetingPoint(MEETING, FINAL)
        port.events.onWaypoints(port.waypoints.toList())
        assertEquals(1800, gateway.progress.value?.remainSeconds)
        assertEquals("50-평창(강릉)", gateway.progress.value?.targetId)
    }

    @Test
    fun `progress is empty until a target is applied`() = runTest {
        val port = FakeNavi()
        val gateway = gateway(port)
        gateway.connect()
        port.events.onWaypoints(listOf(DRIVER_STOP))
        assertNull(gateway.progress.value)
    }

    @Test
    fun `removed waypoint clears remaining time`() = runTest {
        val port = FakeNavi(routing = true, destination = FINAL)
        val gateway = gateway(port)
        gateway.applyMeetingPoint(MEETING, FINAL)
        port.events.onWaypoints(port.waypoints.toList())
        port.events.onWaypoints(emptyList())
        assertNull(gateway.progress.value?.remainSeconds)
    }

    @Test
    fun `meeting at destination reads remaining time from destination info`() = runTest {
        val port = FakeNavi()
        val gateway = gateway(port)
        gateway.applyDestination(FINAL.copy(placeId = "destination"))
        gateway.refreshProgress()
        assertEquals(3600, gateway.progress.value?.remainSeconds)
        assertEquals("destination", gateway.progress.value?.targetId)
    }

    @Test
    fun `refresh asks for waypoints only while tracking a waypoint`() = runTest {
        val port = FakeNavi(routing = true, destination = FINAL)
        val gateway = gateway(port)
        gateway.applyMeetingPoint(MEETING, FINAL)
        gateway.refreshProgress()
        assertEquals(1800, gateway.progress.value?.remainSeconds)
    }

    @Test
    fun `negative remaining time means unknown`() = runTest {
        val port = FakeNavi(routing = true, destination = FINAL)
        val gateway = gateway(port)
        gateway.applyMeetingPoint(MEETING, FINAL)
        port.events.onWaypoints(listOf(waypoint(MEETING, seconds = -1)))
        assertNull(gateway.progress.value?.remainSeconds)
    }

    @Test
    fun `remaining time is seconds even when rounded distance looks too fast`() = runTest {
        val port = FakeNavi(routing = true, destination = FINAL)
        val gateway = gateway(port)
        gateway.applyMeetingPoint(MEETING, FINAL)
        port.events.onWaypoints(listOf(waypoint(MEETING, seconds = 10, meters = 1_000)))
        assertEquals(10, gateway.progress.value?.remainSeconds)
    }

    @Test
    fun `arriving at the meeting waypoint marks arrival`() = runTest {
        val port = FakeNavi(routing = true, destination = FINAL)
        val gateway = gateway(port)
        gateway.applyMeetingPoint(MEETING, FINAL)
        port.events.onWaypointArrived(MEETING.name, WaypointSlot.FIRST)
        assertTrue(gateway.progress.value?.arrived == true)
    }

    @Test
    fun `arriving at the slot of the meeting waypoint marks arrival when names differ`() = runTest {
        val port = FakeNavi(routing = true, destination = FINAL, waypoints = listOf(DRIVER_STOP))
        val gateway = gateway(port)
        gateway.applyMeetingPoint(MEETING, FINAL)
        port.events.onWaypoints(port.waypoints.toList())
        port.events.onWaypointArrived("평창휴게소 입구", WaypointSlot.SECOND)
        assertTrue(gateway.progress.value?.arrived == true)
    }

    @Test
    fun `arriving at a driver waypoint is not arrival at the meeting point`() = runTest {
        val port = FakeNavi(routing = true, destination = FINAL, waypoints = listOf(DRIVER_STOP))
        val gateway = gateway(port)
        gateway.applyMeetingPoint(MEETING, FINAL)
        port.events.onWaypoints(port.waypoints.toList())
        port.events.onWaypointArrived(DRIVER_STOP.name, WaypointSlot.FIRST)
        assertFalse(gateway.progress.value?.arrived == true)
    }

    @Test
    fun `reaching the final destination is not arrival at a waypoint meeting point`() = runTest {
        val port = FakeNavi(routing = true, destination = FINAL)
        val gateway = gateway(port)
        gateway.applyMeetingPoint(MEETING, FINAL)
        port.events.onDestinationArrived(FINAL.name)
        assertFalse(gateway.progress.value?.arrived == true)
    }

    @Test
    fun `reaching the destination counts when meeting there`() = runTest {
        val port = FakeNavi()
        val gateway = gateway(port)
        gateway.applyDestination(FINAL)
        port.events.onDestinationArrived(FINAL.name)
        assertTrue(gateway.progress.value?.arrived == true)
    }

    @Test
    fun `new meeting point starts without the previous arrival`() = runTest {
        val port = FakeNavi(routing = true, destination = FINAL)
        val gateway = gateway(port)
        gateway.applyMeetingPoint(MEETING, FINAL)
        port.events.onWaypointArrived(MEETING.name, WaypointSlot.FIRST)
        gateway.applyMeetingPoint(NEXT, FINAL)
        assertFalse(gateway.progress.value?.arrived == true)
        assertEquals("50-횡성(강릉)", gateway.progress.value?.targetId)
    }

    @Test
    fun `changed plan drops progress until applied again`() = runTest {
        val port = FakeNavi(routing = true, destination = FINAL)
        val gateway = gateway(port)
        gateway.applyMeetingPoint(MEETING, FINAL)
        port.events.onWaypointArrived(MEETING.name, WaypointSlot.FIRST)
        gateway.resetProgress()
        assertNull(gateway.progress.value)
        port.events.onWaypoints(port.waypoints.toList())
        assertNull(gateway.progress.value)
    }

    @Test
    fun `guidance that stays off clears progress after the grace period`() = runTest {
        var now = 0L
        val port = FakeNavi(routing = true, destination = FINAL)
        val store = InMemoryAppliedPointStore()
        val gateway = PleosNaviHelperGateway(portFactory = { port }, appliedPoints = store, clock = { now })
        gateway.applyMeetingPoint(MEETING, FINAL)

        port.routing = false
        port.events.onRouteCancelled()
        gateway.refreshProgress()
        assertEquals("50-평창(강릉)", gateway.progress.value?.targetId)

        now = 61_000
        gateway.refreshProgress()
        assertNull(gateway.progress.value)
        assertNull(store.meetingPoint)
    }

    @Test
    fun `route rebuilt by the driver keeps tracking the meeting point`() = runTest {
        var now = 0L
        val port = FakeNavi(routing = true, destination = FINAL)
        val store = InMemoryAppliedPointStore()
        val gateway = PleosNaviHelperGateway(portFactory = { port }, appliedPoints = store, clock = { now })
        gateway.applyMeetingPoint(MEETING, FINAL)

        // 운전자가 내비에서 경유지를 바꾸면 안내가 잠시 꺼졌다 돌아온다.
        port.events.onRouteCancelled()
        port.events.onGuidanceChanged(false)
        port.events.onRouteEnded()
        port.events.onGuidanceChanged(true)

        now = 120_000
        gateway.refreshProgress()
        assertEquals(1800, gateway.progress.value?.remainSeconds)
        assertEquals(MEETING, store.meetingPoint)
    }

    @Test
    fun `missed guidance callback is recovered from route state`() = runTest {
        var now = 0L
        val port = FakeNavi(routing = true, destination = FINAL)
        val gateway = PleosNaviHelperGateway(portFactory = { port }, clock = { now })
        gateway.applyMeetingPoint(MEETING, FINAL)
        port.events.onRouteCancelled()

        now = 120_000
        gateway.refreshProgress()
        assertEquals("50-평창(강릉)", gateway.progress.value?.targetId)
    }

    @Test
    fun `search end does not stop tracking`() = runTest {
        val port = FakeNavi(routing = true, destination = FINAL)
        val gateway = gateway(port)
        gateway.applyMeetingPoint(MEETING, FINAL)
        port.events.onRouteEnded()
        assertEquals("50-평창(강릉)", gateway.progress.value?.targetId)
    }

    @Test
    fun `lost guidance keeps arrival that already happened`() = runTest {
        var now = 0L
        val port = FakeNavi()
        val gateway = PleosNaviHelperGateway(portFactory = { port }, clock = { now })
        gateway.applyDestination(FINAL)
        port.events.onDestinationArrived(FINAL.name)

        port.routing = false
        port.events.onGuidanceChanged(false)
        now = 61_000
        gateway.refreshProgress()
        assertTrue(gateway.progress.value?.arrived == true)
    }

    @Test
    fun `tracking resumes after restart while the meeting point stays in navigation`() = runTest {
        val store = InMemoryAppliedPointStore().apply { meetingPoint = MEETING }
        val port = FakeNavi(routing = true, destination = FINAL, waypoints = listOf(waypoint(MEETING, 1200, 30_000)))
        val gateway = gateway(port, store)
        gateway.connect()
        gateway.refreshProgress()
        assertEquals(1200, gateway.progress.value?.remainSeconds)
    }

    // ---- 조회·연결 ----

    @Test
    fun `initialization failure makes navigation unavailable and frees the port`() = runTest {
        val port = FakeNavi(connects = false)
        val gateway = gateway(port)
        assertEquals(NavigationResult.Unavailable, gateway.applyMeetingPoint(MEETING, FINAL))
        assertFalse(gateway.supportsVehicleLocation)
        assertTrue(port.released)
    }

    @Test
    fun `port construction failure does not crash`() = runTest {
        val gateway = PleosNaviHelperGateway(portFactory = { throw NoClassDefFoundError("SDK 없음") })
        assertFalse(gateway.connect())
    }

    @Test
    fun `destination and location queries return callback values`() = runTest {
        val gateway = gateway(FakeNavi(destination = FINAL))
        assertEquals(FINAL, gateway.currentDestination())
        assertEquals(37.5, gateway.currentLocation()!!.lat, 0.0)
    }

    @Test
    fun `empty destination reply means no destination`() = runTest {
        assertNull(gateway(FakeNavi()).currentDestination())
    }

    @Test
    fun `query without reply returns null`() = runTest {
        val port = FakeNavi(destination = FINAL).apply { answersDestination = false }
        assertNull(gateway(port).currentDestination())
    }

    @Test
    fun `navigation screen opens only when connected`() = runTest {
        val port = FakeNavi()
        val gateway = gateway(port)
        assertFalse(gateway.openNavigationScreen())
        gateway.connect()
        assertTrue(gateway.openNavigationScreen())
        assertEquals(listOf("openNavigationScreen"), port.calls)
    }

    @Test
    fun `sdk failure when opening navigation screen is reported`() = runTest {
        val gateway = gateway(FakeNavi(throwOnApply = true))
        gateway.connect()
        assertFalse(gateway.openNavigationScreen())
    }

    @Test
    fun `place search opens only when connected`() = runTest {
        val port = FakeNavi()
        val gateway = gateway(port)
        assertFalse(gateway.openPlaceSearch("판교역"))
        gateway.connect()
        assertTrue(gateway.openPlaceSearch("판교역"))
        assertEquals(listOf("openPlaceSearch 판교역"), port.calls)
    }

    @Test
    fun `sdk failure when opening place search is reported`() = runTest {
        val gateway = gateway(FakeNavi(throwOnApply = true))
        gateway.connect()
        assertFalse(gateway.openPlaceSearch("판교역"))
    }

    @Test
    fun `no reply is a failed lookup not an empty destination`() = runTest {
        val port = FakeNavi(destination = FINAL).apply { answersDestination = false }
        assertTrue(gateway(port).lookupDestination().isFailure)
    }

    @Test
    fun `empty reply is a successful lookup without a destination`() = runTest {
        val result = gateway(FakeNavi()).lookupDestination()
        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
    }

    @Test
    fun `failed connection is a failed lookup`() = runTest {
        assertTrue(gateway(FakeNavi(connects = false)).lookupDestination().isFailure)
    }

    @Test
    fun `fallback without pleos cannot open place search`() = runTest {
        val fallback = FallbackNavigationGateway(gateway(FakeNavi(connects = false)), RecordingNavigationGateway())
        fallback.connect()
        assertFalse(fallback.openPlaceSearch("판교역"))
        assertTrue(fallback.lookupDestination().isSuccess)
    }

    @Test
    fun `release frees the port`() = runTest {
        val port = FakeNavi()
        val gateway = gateway(port)
        gateway.connect()
        gateway.release()
        assertTrue(port.released)
        assertFalse(gateway.supportsVehicleLocation)
    }

    // ---- 기록용 구현으로의 전환 ----

    @Test
    fun `fallback gateway keeps app working without pleos navigation`() = runTest {
        val recording = RecordingNavigationGateway()
        val fallback = FallbackNavigationGateway(gateway(FakeNavi(connects = false)), recording)
        assertEquals(NavigationResult.RecordedOnly, fallback.applyMeetingPoint(MEETING, FINAL))
        assertEquals("내비 없음(기록만 함)", fallback.label)
        assertEquals(1, recording.requests.size)
        assertFalse(fallback.openNavigationScreen())
    }

    @Test
    fun `fallback returns to pleos when a later connection succeeds`() = runTest {
        var available = false
        val port = FakeNavi()
        val primary = PleosNaviHelperGateway(portFactory = {
            if (available) port else FakeNavi(connects = false)
        })
        val fallback = FallbackNavigationGateway(primary, RecordingNavigationGateway())
        fallback.connect()
        assertEquals("내비 없음(기록만 함)", fallback.label)

        available = true
        fallback.connect()
        assertEquals("Pleos NaviHelper", fallback.label)
        assertTrue(fallback.supportsVehicleLocation)
    }

    @Test
    fun `background callers respect the retry interval`() = runTest {
        var attempts = 0
        var now = 0L
        val primary = PleosNaviHelperGateway(portFactory = {
            attempts += 1
            FakeNavi(connects = false)
        })
        val fallback = FallbackNavigationGateway(primary, RecordingNavigationGateway(), 30_000) { now }
        fallback.connect()
        fallback.ensureConnected()
        assertEquals(1, attempts)
        now = 30_000
        fallback.ensureConnected()
        assertEquals(2, attempts)
    }

    @Test
    fun `progress observers keep working after returning to pleos`() = runTest {
        var available = false
        val port = FakeNavi()
        val primary = PleosNaviHelperGateway(portFactory = {
            if (available) port else FakeNavi(connects = false)
        })
        val fallback = FallbackNavigationGateway(primary, RecordingNavigationGateway())
        fallback.connect()
        val observed = fallback.progress

        available = true
        fallback.connect()
        fallback.applyMeetingPoint(MEETING, FINAL)
        assertEquals("50-평창(강릉)", observed.value?.targetId)
    }
}
