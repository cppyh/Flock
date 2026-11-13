package com.meetroute.app.data

import com.meetroute.app.domain.ConnectionState
import com.meetroute.app.domain.Destination
import com.meetroute.app.domain.DriveTheme
import com.meetroute.app.domain.GroupState
import com.meetroute.app.domain.MeetPointRepository
import com.meetroute.app.domain.MeetRouteException
import com.meetroute.app.domain.MeetingPurpose
import com.meetroute.app.domain.NavProgress
import com.meetroute.app.domain.RecommendationSet
import com.meetroute.app.domain.Session
import com.meetroute.app.domain.SessionEvent
import com.meetroute.app.domain.StoredSession
import com.meetroute.app.location.LocationFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import java.time.Instant

@Serializable
private data class CreateGroupBody(val name: String, val nickname: String)

@Serializable
private data class JoinGroupBody(@SerialName("invite_code") val inviteCode: String, val nickname: String)

@Serializable
private data class PurposeBody(
    @SerialName("meeting_purpose") val purpose: MeetingPurpose,
    @SerialName("meal_time") val mealTime: String?,
)

@Serializable
private data class SharingBody(val enabled: Boolean)

@Serializable
private data class FreeDriveBody(
    val hours: Int,
    val theme: DriveTheme,
    @SerialName("private") val isPrivate: Boolean,
    val reroll: Boolean,
)

@Serializable
private data class SelectBody(
    @SerialName("recommendation_id") val recommendationId: String,
    @SerialName("place_id") val placeId: String,
    @SerialName("expected_version") val expectedVersion: Int,
)

@Serializable
data class LocationPayload(
    val lat: Double,
    val lon: Double,
    val timestamp: String,
    val heading: Double? = null,
    @SerialName("accuracy_m") val accuracyMeters: Double? = null,
    val source: String,
    @SerialName("nav_remain_seconds") val navRemainSeconds: Double? = null,
    @SerialName("arrived_by_nav") val arrivedByNav: Boolean = false,
    @SerialName("nav_target_id") val navTargetId: String? = null,
    @SerialName("battery_percent") val batteryPercent: Double? = null,
)

@Serializable
private data class LocationMessage(val type: String, val payload: LocationPayload)

fun LocationFix.toPayload(progress: NavProgress?) = LocationPayload(
    lat = lat,
    lon = lon,
    timestamp = Instant.ofEpochMilli(timestampMillis).toString(),
    // NaN은 JSON으로 만들 수 없고, 음수는 서버가 위치 전체를 거부하므로 값이 없는 것으로 보낸다.
    heading = heading?.takeIf(Double::isFinite),
    accuracyMeters = accuracyMeters?.takeIf { it.isFinite() && it >= 0 },
    source = source.wireName,
    navRemainSeconds = progress?.remainSeconds?.takeIf { it >= 0 }?.toDouble(),
    arrivedByNav = progress?.arrived == true,
    navTargetId = progress?.targetId,
    // 서버는 0~100만 받는다. 범위를 벗어난 값은 읽지 못한 것으로 보고 보내지 않는다.
    batteryPercent = batteryPercent?.takeIf { it.isFinite() && it in 0.0..100.0 },
)

/** 실제 서버 연동 구현. 도로 경로와 휴게소 공공데이터는 서버의 추천 엔진이 처리한다. */
class RoadRoutingMeetPointRepository(
    private val serverUrl: String,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) : MeetPointRepository {

    private val api = ApiClient(serverUrl, http)
    private var realtime: RealtimeClient? = null
    private var channelJobs: List<Job> = emptyList()
    private var groupId: String? = null

    private val _connection = MutableStateFlow(ConnectionState.OFFLINE)
    override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val resyncEvents = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 8)
    private val realtimeEvents = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 64)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val events: Flow<SessionEvent> = merge(realtimeEvents, resyncEvents)

    override val dataLabel: String? = null

    private fun groupPath(suffix: String = ""): String {
        val id = groupId ?: throw MeetRouteException("NO_SESSION", "참여 중인 그룹이 없습니다.")
        return "/api/v1/groups/$id$suffix"
    }

    override suspend fun createGroup(name: String, nickname: String): Session {
        val body = ApiJson.encodeToString(CreateGroupBody(name, nickname))
        return startSession(ApiJson.decodeFromString(api.post("/api/v1/groups", body)))
    }

    override suspend fun joinGroup(inviteCode: String, nickname: String): Session {
        val body = ApiJson.encodeToString(JoinGroupBody(inviteCode.trim().uppercase(), nickname))
        return startSession(ApiJson.decodeFromString(api.post("/api/v1/groups/join", body)))
    }

    private fun startSession(session: Session): Session {
        api.token = session.accessToken
        groupId = session.group.groupId
        return session
    }

    override suspend fun resume(session: StoredSession): GroupState {
        api.token = session.accessToken
        groupId = session.groupId
        return refresh()
    }

    override fun connectRealtime() {
        val id = groupId ?: return
        val token = api.token ?: return
        if (realtime != null) return
        val client = RealtimeClient(http, webSocketUrl(id), token, scope, ::resync)
        realtime = client
        channelJobs = listOf(
            scope.launch { client.state.collect { _connection.value = it } },
            scope.launch { client.events.collect(realtimeEvents::emit) },
        )
        client.connect()
    }

    /** 연결되기 전이나 끊긴 동안 놓친 이벤트를 최신 상태 조회로 메운다. */
    private suspend fun resync() {
        resyncEvents.emit(SessionEvent.GroupUpdated(refresh()))
        resyncEvents.emit(SessionEvent.RecommendationsSynced(latestRecommendations()))
    }

    private suspend fun latestRecommendations(): RecommendationSet? = try {
        ApiJson.decodeFromString(api.get(groupPath("/recommendations")))
    } catch (error: MeetRouteException) {
        if (error.code == "RECOMMENDATION_NOT_FOUND") null else throw error
    }

    private fun webSocketUrl(id: String): String =
        serverUrl.trimEnd('/').replaceFirst("http", "ws") + "/ws/groups/$id"

    override suspend fun refresh(): GroupState = ApiJson.decodeFromString(api.get(groupPath()))

    override suspend fun setDestination(destination: Destination): GroupState =
        ApiJson.decodeFromString(api.patch(groupPath("/destination"), ApiJson.encodeToString(destination)))

    override suspend fun setPurpose(purpose: MeetingPurpose, mealTime: String?): GroupState {
        val body = ApiJson.encodeToString(PurposeBody(purpose, mealTime))
        return ApiJson.decodeFromString(api.patch(groupPath("/meeting-purpose"), body))
    }

    override suspend fun startFreeDrive(
        hours: Int,
        theme: DriveTheme,
        privateDestination: Boolean,
        reroll: Boolean,
    ): GroupState {
        val body = ApiJson.encodeToString(FreeDriveBody(hours, theme, privateDestination, reroll))
        return ApiJson.decodeFromString(api.post(groupPath("/free-drive"), body))
    }

    override suspend fun setLocationSharing(enabled: Boolean) {
        api.patch(groupPath("/location-sharing"), ApiJson.encodeToString(SharingBody(enabled)))
    }

    override suspend fun publishLocation(fix: LocationFix, progress: NavProgress?) {
        val payload = fix.toPayload(progress)
        val message = ApiJson.encodeToString(LocationMessage("location.updated", payload))
        // 실시간 채널이 끊겼으면 백업 경로인 REST로 보낸다.
        if (realtime?.send(message) != true) {
            api.post(groupPath("/locations"), ApiJson.encodeToString(payload))
        }
    }

    override suspend fun requestRecommendations(): RecommendationSet =
        ApiJson.decodeFromString(api.post(groupPath("/recommendations")))

    override suspend fun selectMeetingPoint(
        recommendationId: String,
        placeId: String,
        expectedVersion: Int,
    ): GroupState {
        val body = ApiJson.encodeToString(SelectBody(recommendationId, placeId, expectedVersion))
        return ApiJson.decodeFromString(api.post(groupPath("/meeting-point"), body))
    }

    override suspend fun leave() {
        api.post(groupPath("/leave"))
        close()
    }

    override suspend fun complete() {
        api.post(groupPath("/complete"))
        close()
    }

    override fun close() {
        realtime?.close()
        realtime = null
        channelJobs.forEach(Job::cancel)
        _connection.value = ConnectionState.CLOSED
    }
}
