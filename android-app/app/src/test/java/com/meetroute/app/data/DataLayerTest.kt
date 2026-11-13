package com.meetroute.app.data

import com.meetroute.app.domain.ConnectionState
import com.meetroute.app.domain.DriveTheme
import com.meetroute.app.domain.GroupPhase
import com.meetroute.app.domain.MeetRouteException
import com.meetroute.app.domain.MeetingPurpose
import com.meetroute.app.domain.NavProgress
import com.meetroute.app.domain.ParticipantStatus
import com.meetroute.app.domain.RecommendationSet
import com.meetroute.app.domain.SessionEvent
import com.meetroute.app.location.LocationFix
import com.meetroute.app.location.LocationSourceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// 서버가 실제로 만든 추천 응답. 앱과 서버의 API 계약이 어긋나면 이 파일을 읽는 시험이 깨진다.
private val RECORDED_DEMO = File("src/main/assets/demo/gangneung.json").readText()

private val RECORDED_RECOMMENDATIONS: String =
    Json.parseToJsonElement(RECORDED_DEMO).jsonObject.getValue("recommendations").toString()

private const val PARTICIPANT_JSON =
    """{"vehicle_id":"car-1","nickname":"수원","role":"MEMBER","is_simulated":true,"status":"ACTIVE","online":true,
    "location_sharing":true,"lat":37.3,"lon":127.0,"heading":null,"accuracy_m":null,"location_source":"SIMULATOR",
    "timestamp":"2026-11-07T00:00:00Z","eta_seconds":120.5,"eta_source":"ROAD_ROUTE",
    "eta_updated_at":"2026-11-07T00:00:00Z","delayed":false,"arrived":false}"""

private const val GROUP_JSON =
    """{"group_id":"g1","name":"강릉 여행","invite_code":"ABC234","invite_expires_at":"2026-11-08T00:00:00Z",
    "max_vehicles":3,"destination":{"name":"강릉역","lat":37.7,"lon":128.8},"meeting_purpose":"REST",
    "meal_time":null,"phase":"FORMING","version":2,"participants":[$PARTICIPANT_JSON],
    "selected_meeting_point_id":null,"meeting_point":null,"reroute_suggested":false}"""

private const val SESSION_JSON =
    """{"group":$GROUP_JSON,"vehicle_id":"car-1","access_token":"token-1","token_expires_at":"2026-11-08T00:00:00Z"}"""

private fun envelope(type: String, payload: String) =
    """{"type":"$type","group_id":"g1","timestamp":"2026-11-07T00:00:00Z","payload":$payload,"event_id":"e1"}"""

class ServerContractTest {

    @Test
    fun `recorded server recommendation parses completely`() {
        val set = ApiJson.decodeFromString<RecommendationSet>(RECORDED_RECOMMENDATIONS)
        assertTrue(set.recommendations.isNotEmpty())
        assertTrue(set.recommendations.size <= 3)
        val first = set.recommendations.first()
        assertEquals(1, first.rank)
        assertTrue(first.reason.isNotEmpty())
        assertEquals(3, first.arrivalEta.size)
        assertTrue(set.destinationComparison.isDestination)
        assertEquals("OSRM", set.routeSource)
    }

    @Test
    fun `participant event parses`() {
        val event = parseServerEvent(envelope("participant.updated", PARTICIPANT_JSON))
        val participant = (event as SessionEvent.ParticipantUpdated).participant
        assertEquals("car-1", participant.vehicleId)
        assertEquals(ParticipantStatus.ACTIVE, participant.status)
        assertEquals(120.5, participant.etaSeconds!!, 0.0)
        assertTrue(participant.isSimulated)
    }

    @Test
    fun `group events parse`() {
        val updated = parseServerEvent(envelope("group.updated", """{"group":$GROUP_JSON}"""))
        assertEquals(MeetingPurpose.REST, (updated as SessionEvent.GroupUpdated).group.meetingPurpose)
        val selected = parseServerEvent(
            envelope("meeting_point.selected", """{"group":$GROUP_JSON,"selected_by":"car-1","at":"2026-11-07T00:00:00Z"}""")
        )
        assertTrue(selected is SessionEvent.GroupUpdated)
    }

    @Test
    fun `recommendation event parses`() {
        val event = parseServerEvent(envelope("recommendations.ready", RECORDED_RECOMMENDATIONS))
        assertTrue(event is SessionEvent.RecommendationsReady)
    }

    @Test
    fun `session closed and error events parse`() {
        assertEquals(
            SessionEvent.SessionClosed("COMPLETED"),
            parseServerEvent(envelope("session.closed", """{"reason":"COMPLETED"}""")),
        )
        assertEquals(
            SessionEvent.ServerError("CONSENT_REQUIRED", "동의 필요"),
            parseServerEvent(envelope("error", """{"code":"CONSENT_REQUIRED","message":"동의 필요"}""")),
        )
    }

    @Test
    fun `unknown and malformed events are ignored`() {
        assertNull(parseServerEvent(envelope("future.event", "{}")))
        assertNull(parseServerEvent("not json"))
        assertNull(parseServerEvent(envelope("participant.updated", """{"broken":true}""")))
    }

    @Test
    fun `location message matches server fields`() {
        val fix = LocationFix(37.5, 127.0, 1_800_000_000_000, heading = 90.0, source = LocationSourceType.PLEOS_FUSED)
        val payload = fix.toPayload(NavProgress(remainSeconds = 600, arrived = true, targetId = "50-평창(강릉)"))
        val json = Json.parseToJsonElement(ApiJson.encodeToString(LocationPayload.serializer(), payload)).jsonObject
        assertEquals("PLEOS_FUSED", json.getValue("source").toString().trim('"'))
        assertEquals("600.0", json.getValue("nav_remain_seconds").toString())
        assertEquals("true", json.getValue("arrived_by_nav").toString())
        assertEquals("50-평창(강릉)", json.getValue("nav_target_id").toString().trim('"'))
        assertTrue(json.getValue("timestamp").toString().endsWith("Z\""))
    }

    @Test
    fun `values the server or json cannot take are left out`() {
        val fix = LocationFix(37.5, 127.0, 1_800_000_000_000, heading = Double.NaN, source = LocationSourceType.PLEOS_FUSED)
        val payload = fix.toPayload(NavProgress(remainSeconds = -1))
        val json = Json.parseToJsonElement(ApiJson.encodeToString(LocationPayload.serializer(), payload)).jsonObject
        assertFalse("heading" in json)
        assertFalse("nav_remain_seconds" in json)
        assertFalse("battery_percent" in json)
    }

    @Test
    fun `battery level is sent with the location`() {
        val fix = LocationFix(37.5, 127.0, 1_800_000_000_000, source = LocationSourceType.PLEOS_FUSED, batteryPercent = 42.0)
        val json = Json.parseToJsonElement(ApiJson.encodeToString(LocationPayload.serializer(), fix.toPayload(null))).jsonObject
        assertEquals("42.0", json.getValue("battery_percent").toString())
        val broken = fix.copy(batteryPercent = 140.0).toPayload(null)
        assertNull(broken.batteryPercent)
    }

    @Test
    fun `private drive group parses for a member`() {
        val event = parseServerEvent(envelope("group.updated", """{"group":$HIDDEN_GROUP_JSON}"""))
        val group = (event as SessionEvent.GroupUpdated).group
        assertNull(group.destination)
        assertTrue(group.destinationHidden)
        assertTrue(group.hasDestination)
        val drive = group.freeDrive!!
        assertEquals(DriveTheme.FOREST, drive.theme)
        assertEquals(2, drive.hours)
        assertTrue(drive.isPrivate)
        assertFalse(drive.revealed)
        assertEquals("p1", drive.planId)
        assertEquals(42.0, group.participants.single().batteryPercent!!, 0.0)
    }

    @Test
    fun `hidden destination candidate and charging plan parse`() {
        val set = ApiJson.decodeFromString<RecommendationSet>(HIDDEN_RECOMMENDATIONS_JSON)
        val hidden = set.destinationComparison
        assertEquals("비공개 목적지", hidden.name)
        assertFalse(hidden.hasCoordinates)
        val plan = set.recommendations.single().evPlan!!.getValue("car-1")
        assertTrue(plan.needsCharge && plan.chargerAvailable)
        assertEquals(18.3, plan.chargeMinutes, 0.0)
        assertEquals(12.0, plan.coveredByWaitMinutes, 0.0)
    }
}

// 서버 views.py·recommendations.py의 비공개 드라이브 그룹원용 응답 모양.
private const val HIDDEN_GROUP_JSON =
    """{"group_id":"g1","name":"드라이브","invite_code":"ABC234","invite_expires_at":"2026-11-08T00:00:00Z",
    "max_vehicles":3,"destination":null,"destination_hidden":true,
    "free_drive":{"plan_id":"p1","hours":2,"theme":"FOREST","theme_label":"숲길","private":true,"revealed":false,
    "round_trip_minutes":118,"one_way_minutes":59,"tolerance_minutes":15.0,"within_tolerance":true,"notice":null,
    "region":null,"description":null},
    "meeting_purpose":"ANY","meal_time":null,"phase":"FORMING","version":3,
    "participants":[{"vehicle_id":"car-1","nickname":"수원","role":"MEMBER","is_simulated":false,"status":"ACTIVE",
    "online":true,"location_sharing":true,"lat":37.3,"lon":127.0,"heading":null,"accuracy_m":null,
    "location_source":"PLEOS_FUSED","timestamp":"2026-11-07T00:00:00Z","battery_percent":42.0,"eta_seconds":null,
    "eta_source":null,"eta_updated_at":null,"delayed":false,"arrived":false}],
    "selected_meeting_point_id":null,"meeting_point":null,"reroute_suggested":false}"""

private const val CANDIDATE_TIMES =
    """"estimated_group_meeting_time":"2026-11-07T01:00:00Z","per_vehicle_arrival_eta":{"car-1":"2026-11-07T00:48:00Z"},
    "per_vehicle_detour_minutes":{"car-1":0.0},"per_vehicle_wait_minutes":{"car-1":12.0},"total_detour_minutes":0.0,
    "total_wait_minutes":12.0,"max_detour_minutes":0.0,"max_wait_minutes":12.0,"shared_driving_minutes":40.0"""

private const val HIDDEN_RECOMMENDATIONS_JSON =
    """{"recommendation_id":"r1","group_id":"g1","computed_at":"2026-11-07T00:00:00Z",
    "recommendations":[{"rank":1,"place_id":"ra","name":"중간휴게소","latitude":37.5,"longitude":127.5,
    "direction":"양방향","route_name":"시험선",$CANDIDATE_TIMES,"parking_or_stop_suitability":0.9,"parking_spaces":200,
    "has_restaurant":true,"has_ev_charger":true,"data_date":"2026-09-17","data_uncertain":false,
    "is_destination_fallback":false,"ev_plan":{"car-1":{"battery_percent":30,"battery_at_arrival":11,
    "battery_at_destination":2,"needs_charge":true,"charger_available":true,"charge_minutes":18.3,
    "covered_by_wait_minutes":12.0,"extra_wait_minutes":6.3,"bonus_percent":0}},"ev_covered_minutes":12.0,
    "ev_extra_minutes":6.3,"score":0.1,"reason":"총 우회 0분 / 최대 대기 12분 / 충전 18분 중 12분 대기와 겹침"}],
    "destination_comparison":{"rank":null,"place_id":"destination","name":"비공개 목적지","latitude":null,
    "longitude":null,"direction":null,"route_name":null,$CANDIDATE_TIMES,"parking_or_stop_suitability":null,
    "parking_spaces":null,"has_restaurant":null,"has_ev_charger":null,"data_date":null,"data_uncertain":false,
    "is_destination_fallback":true,"ev_plan":null,"ev_covered_minutes":0.0,"ev_extra_minutes":0.0,"score":0.5,
    "reason":"총 우회 0분"},
    "notice":null,"meet_window":null,"window_expanded":false,"change_recommended":null,"route_source":"OSRM",
    "traffic_aware":false,"excluded_vehicles":[],"stats":{},"stale":false}"""

class RealtimeClientTest {

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var http: OkHttpClient
    private val connects = AtomicInteger()

    @Before
    fun setUp() {
        server = MockWebServer()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        http = OkHttpClient.Builder().pingInterval(200, TimeUnit.MILLISECONDS).build()
    }

    @After
    fun tearDown() {
        scope.cancel()
        http.dispatcher.executorService.shutdownNow()
        http.connectionPool.evictAll()
        server.shutdown()
    }

    private fun serverCloses(code: Int) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                connects.incrementAndGet()
                return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.close(code, "서버가 닫음")
                    }
                })
            }
        }
        server.start()
    }

    private fun client(onConnected: suspend () -> Unit = {}) =
        RealtimeClient(http, server.url("/").toString().replaceFirst("http", "ws"), "token", scope, onConnected)

    private fun awaitState(client: RealtimeClient, expected: ConnectionState) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline && client.state.value != expected) Thread.sleep(10)
        assertEquals(expected, client.state.value)
    }

    @Test
    fun `terminal close ends the connection without reconnecting`() {
        for (code in listOf(4401, 4403, 4409, 4410)) {
            server = MockWebServer()
            connects.set(0)
            serverCloses(code)
            val client = client()
            client.connect()
            awaitState(client, ConnectionState.CLOSED)
            Thread.sleep(2_500)
            assertEquals("종료 코드 $code", 1, connects.get())
            server.shutdown()
        }
        server = MockWebServer()
    }

    @Test
    fun `other close codes reconnect`() {
        serverCloses(1011)
        val client = client()
        client.connect()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline && connects.get() < 2) Thread.sleep(10)
        assertTrue(connects.get() >= 2)
        client.close()
    }

    @Test
    fun `every connection including the first resynchronizes`() {
        val resyncs = AtomicInteger()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }
            })
        )
        server.start()
        val client = client { resyncs.incrementAndGet() }
        client.connect()
        awaitState(client, ConnectionState.CONNECTED)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline && resyncs.get() == 0) Thread.sleep(10)
        assertEquals(1, resyncs.get())
        client.close()
    }

    @Test
    fun `nothing is queued before the connection opens`() {
        server.start()
        val client = client()
        assertFalse(client.send("{}"))
    }
}

class RoadRoutingRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var repository: RoadRoutingMeetPointRepository
    private val restResponses = ConcurrentLinkedQueue<MockResponse>()

    @Before
    fun setUp() {
        server = MockWebServer()
        // 실시간 채널 연결 요청이 REST 응답을 가로채지 않도록 경로로 나눈다.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path.orEmpty().startsWith("/ws/")) {
                    MockResponse().setResponseCode(404)
                } else {
                    restResponses.poll() ?: MockResponse().setResponseCode(500)
                }
        }
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        repository = RoadRoutingMeetPointRepository(server.url("/").toString(), OkHttpClient(), scope)
    }

    @After
    fun tearDown() {
        repository.close()
        scope.cancel()
        server.shutdown()
    }

    private fun enqueue(status: Int, body: String) {
        restResponses.add(MockResponse().setResponseCode(status).setBody(body))
    }

    private fun recordedRequests(): List<RecordedRequest> =
        generateSequence { server.takeRequest(1, TimeUnit.SECONDS) }.toList()

    @Test
    fun `create group uses token for later requests`() = runTest {
        enqueue(201, SESSION_JSON)
        val session = repository.createGroup("강릉", "서울")
        assertEquals("ABC234", session.group.inviteCode)

        enqueue(200, GROUP_JSON)
        repository.refresh()
        val request = recordedRequests().first { it.path == "/api/v1/groups/g1" }
        assertEquals("Bearer token-1", request.getHeader("Authorization"))
    }

    @Test
    fun `free drive request carries hours theme and visibility`() = runTest {
        enqueue(201, SESSION_JSON)
        repository.createGroup("드라이브", "서울")
        enqueue(200, HIDDEN_GROUP_JSON)
        val group = repository.startFreeDrive(3, DriveTheme.NIGHT, privateDestination = true, reroll = true)
        assertTrue(group.destinationHidden)
        val request = recordedRequests().first { it.path == "/api/v1/groups/g1/free-drive" }
        assertEquals("POST", request.method)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("3", body.getValue("hours").toString())
        assertEquals("\"NIGHT\"", body.getValue("theme").toString())
        assertEquals("true", body.getValue("private").toString())
        assertEquals("true", body.getValue("reroll").toString())
    }

    @Test
    fun `invite code is normalized before sending`() = runTest {
        enqueue(201, SESSION_JSON)
        repository.joinGroup(" abc234 ", "수원")
        assertTrue(server.takeRequest().body.readUtf8().contains("ABC234"))
    }

    @Test
    fun `server error code reaches caller`() = runTest {
        enqueue(410, """{"error":{"code":"INVITE_EXPIRED","message":"초대 코드가 만료되었습니다."}}""")
        try {
            repository.joinGroup("ABC234", "수원")
            fail("예외가 나야 합니다.")
        } catch (error: MeetRouteException) {
            assertEquals("INVITE_EXPIRED", error.code)
            assertEquals("초대 코드가 만료되었습니다.", error.message)
        }
    }

    @Test
    fun `validation error without error body is handled`() = runTest {
        enqueue(422, """{"detail":[{"msg":"bad"}]}""")
        try {
            repository.joinGroup("ABC234", "수원")
            fail("예외가 나야 합니다.")
        } catch (error: MeetRouteException) {
            assertEquals("HTTP_422", error.code)
        }
    }

    @Test
    fun `routing outage carries last good recommendation`() = runTest {
        enqueue(201, SESSION_JSON)
        repository.createGroup("강릉", "서울")
        enqueue(
            503,
            """{"error":{"code":"ROUTING_UNAVAILABLE","message":"경로 오류","last_good":$RECORDED_RECOMMENDATIONS}}""",
        )
        try {
            repository.requestRecommendations()
            fail("예외가 나야 합니다.")
        } catch (error: MeetRouteException) {
            assertEquals("ROUTING_UNAVAILABLE", error.code)
            assertNotNull(error.lastGood)
        }
    }

    @Test
    fun `network failure becomes readable error`() = runTest {
        server.shutdown()
        try {
            repository.joinGroup("ABC234", "수원")
            fail("예외가 나야 합니다.")
        } catch (error: MeetRouteException) {
            assertEquals("NETWORK", error.code)
        }
    }

    @Test
    fun `location falls back to rest while realtime channel is down`() = runTest {
        enqueue(201, SESSION_JSON)
        repository.createGroup("강릉", "서울")
        enqueue(202, """{"accepted":true}""")
        val fix = LocationFix(37.5, 127.0, Instant.now().toEpochMilli(), source = LocationSourceType.ANDROID)
        repository.publishLocation(fix, null)
        val paths = recordedRequests().map { it.path }
        assertTrue(paths.toString(), "/api/v1/groups/g1/locations" in paths)
    }

    @Test
    fun `invalid server address becomes readable error`() = runTest {
        val broken = RoadRoutingMeetPointRepository("서버 주소 아님", OkHttpClient(), scope)
        try {
            broken.joinGroup("ABC234", "수원")
            fail("예외가 나야 합니다.")
        } catch (error: MeetRouteException) {
            assertEquals("INVALID_SERVER_URL", error.code)
        }
    }

    @Test
    fun `creating a group does not open the channel before events are observed`() = runTest {
        enqueue(201, SESSION_JSON)
        repository.createGroup("강릉", "서울")
        assertEquals(ConnectionState.OFFLINE, repository.connection.value)
        assertTrue(recordedRequests().none { it.path.orEmpty().startsWith("/ws/") })
    }

    @Test
    fun `calling group api without session fails clearly`() = runTest {
        try {
            repository.refresh()
            fail("예외가 나야 합니다.")
        } catch (error: MeetRouteException) {
            assertEquals("NO_SESSION", error.code)
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FixtureRepositoryTest {

    private fun repositoryIn(scope: CoroutineScope) =
        FixtureMeetPointRepository(RECORDED_DEMO, scope, timeScale = 3600.0, tickMillis = 1000)

    @Test
    fun `demo runs from group creation to arrival without a server`() = runTest {
        val repository = repositoryIn(backgroundScope)
        val session = repository.createGroup("", "")
        assertEquals(3, session.group.participants.size)
        assertTrue(session.group.participants.all { it.isSimulated })
        assertEquals(ConnectionState.OFFLINE, repository.connection.value)
        assertNotNull(repository.dataLabel)

        repository.setLocationSharing(true)
        assertTrue(repository.refresh().participants.all { it.lat != null })

        val set = repository.requestRecommendations()
        val best = set.recommendations.first()
        val group = repository.selectMeetingPoint(set.recommendationId, best.placeId, session.group.version)
        assertEquals(GroupPhase.EN_ROUTE, group.phase)

        testScheduler.advanceTimeBy(30_000)
        testScheduler.runCurrent()
        val arrived = repository.refresh()
        assertEquals(GroupPhase.ALL_ARRIVED, arrived.phase)
        assertTrue(arrived.participants.all { it.arrived && it.etaSeconds == 0.0 })
        assertEquals(best.latitude!!, arrived.participants.first().lat!!, 1e-9)
    }

    @Test
    fun `recorded times are shifted to the present`() = runTest {
        val repository = repositoryIn(backgroundScope)
        val set = repository.requestRecommendations()
        val meeting = Instant.parse(set.recommendations.first().meetingTime)
        assertTrue(meeting.isAfter(Instant.now()))
    }

    @Test
    fun `withdrawing consent clears demo locations`() = runTest {
        val repository = repositoryIn(backgroundScope)
        repository.setLocationSharing(true)
        repository.setLocationSharing(false)
        assertTrue(repository.refresh().participants.all { it.lat == null })
        assertFalse(repository.refresh().participants.first().locationSharing)
        advanceUntilIdle()
    }

    @Test
    fun `joining is not available in demo`() = runTest {
        try {
            repositoryIn(backgroundScope).joinGroup("ABC234", "x")
            fail("예외가 나야 합니다.")
        } catch (error: MeetRouteException) {
            assertEquals("DEMO", error.code)
        }
    }
}
