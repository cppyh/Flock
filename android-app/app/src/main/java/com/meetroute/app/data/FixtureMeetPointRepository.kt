package com.meetroute.app.data

import com.meetroute.app.domain.ConnectionState
import com.meetroute.app.domain.Destination
import com.meetroute.app.domain.DriveTheme
import com.meetroute.app.domain.GroupPhase
import com.meetroute.app.domain.GroupState
import com.meetroute.app.domain.MeetPointRepository
import com.meetroute.app.domain.MeetRouteException
import com.meetroute.app.domain.MeetingCandidate
import com.meetroute.app.domain.MeetingPurpose
import com.meetroute.app.domain.NavProgress
import com.meetroute.app.domain.Participant
import com.meetroute.app.domain.ParticipantStatus
import com.meetroute.app.domain.RecommendationSet
import com.meetroute.app.domain.Session
import com.meetroute.app.domain.SessionEvent
import com.meetroute.app.domain.StoredSession
import com.meetroute.app.location.LocationFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

private const val TICK_MILLIS = 1_000L
const val DEMO_ASSET_PATH = "demo/gangneung.json"

@Serializable
private data class DemoVehicle(
    @SerialName("vehicle_id") val vehicleId: String,
    val nickname: String,
    val lat: Double,
    val lon: Double,
)

@Serializable
private data class DemoScenario(
    @SerialName("group_name") val groupName: String,
    val destination: Destination,
    @SerialName("meeting_purpose") val purpose: MeetingPurpose,
    @SerialName("my_vehicle_id") val myVehicleId: String,
    val vehicles: List<DemoVehicle>,
    val recommendations: RecommendationSet,
)

/**
 * 서버 없이 녹화된 시나리오를 재생한다(발표 현장 네트워크 장애 대비).
 *
 * 추천 결과는 실제 계산 결과의 녹화본이고, 차량 이동은 시간을 압축해 재현한다.
 */
class FixtureMeetPointRepository(
    scenarioJson: String,
    private val scope: CoroutineScope,
    private val timeScale: Double = 120.0,
    private val clock: () -> Instant = Instant::now,
    private val tickMillis: Long = TICK_MILLIS,
) : MeetPointRepository {

    private val scenario = ApiJson.decodeFromString<DemoScenario>(scenarioJson)
    private val eventFlow = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 32)
    override val events: Flow<SessionEvent> = eventFlow

    private val _connection = MutableStateFlow(ConnectionState.OFFLINE)
    override val connection: StateFlow<ConnectionState> = _connection

    override val dataLabel = "오프라인 시연 모드 · 녹화 데이터 재생 중"

    private var group = GroupState(
        groupId = "demo-group",
        name = scenario.groupName,
        inviteCode = "DEMO00",
        destination = scenario.destination,
        meetingPurpose = scenario.purpose,
        participants = scenario.vehicles.mapIndexed { index, vehicle ->
            Participant(
                vehicleId = vehicle.vehicleId,
                nickname = vehicle.nickname,
                role = if (index == 0) "LEADER" else "MEMBER",
                isSimulated = true,
                online = true,
            )
        },
    )
    private var recommendations: RecommendationSet? = null
    private var timeline: Job? = null

    override suspend fun createGroup(name: String, nickname: String): Session =
        Session(group, scenario.myVehicleId, accessToken = "")

    override suspend fun joinGroup(inviteCode: String, nickname: String): Session =
        throw MeetRouteException("DEMO", "시연 모드에서는 그룹 참여를 쓸 수 없습니다.")

    override suspend fun resume(session: StoredSession): GroupState = group

    override suspend fun refresh(): GroupState = group

    override suspend fun setDestination(destination: Destination): GroupState = group

    override suspend fun setPurpose(purpose: MeetingPurpose, mealTime: String?): GroupState = group

    override suspend fun startFreeDrive(
        hours: Int,
        theme: DriveTheme,
        privateDestination: Boolean,
        reroll: Boolean,
    ): GroupState = throw MeetRouteException("DEMO", "시연 모드에서는 드라이브 코스 추천을 쓸 수 없습니다.")

    override suspend fun setLocationSharing(enabled: Boolean) {
        val now = clock().toString()
        group = group.copy(
            participants = group.participants.map { participant ->
                val start = scenario.vehicles.first { it.vehicleId == participant.vehicleId }
                participant.copy(
                    locationSharing = enabled,
                    status = if (enabled) ParticipantStatus.ACTIVE else ParticipantStatus.PAUSED,
                    lat = start.lat.takeIf { enabled },
                    lon = start.lon.takeIf { enabled },
                    timestamp = now.takeIf { enabled },
                    locationSource = "RECORDED",
                )
            }
        )
        publishGroup()
    }

    override suspend fun publishLocation(fix: LocationFix, progress: NavProgress?) = Unit

    override suspend fun requestRecommendations(): RecommendationSet {
        val result = shifted(scenario.recommendations)
        recommendations = result
        eventFlow.tryEmit(SessionEvent.RecommendationsReady(result))
        return result
    }

    /** 녹화 시각을 현재로 옮겨 화면의 예상 시각이 현실적으로 보이게 한다. */
    private fun shifted(set: RecommendationSet): RecommendationSet {
        val offset = Duration.between(Instant.parse(set.computedAt), clock())
        fun shift(time: String) = Instant.parse(time).plus(offset).toString()
        fun shift(candidate: MeetingCandidate) = candidate.copy(
            meetingTime = shift(candidate.meetingTime),
            arrivalEta = candidate.arrivalEta.mapValues { shift(it.value) },
        )
        return set.copy(
            computedAt = shift(set.computedAt),
            recommendations = set.recommendations.map(::shift),
            destinationComparison = shift(set.destinationComparison),
        )
    }

    override suspend fun selectMeetingPoint(
        recommendationId: String,
        placeId: String,
        expectedVersion: Int,
    ): GroupState {
        val current = checkNotNull(recommendations)
        val point = (current.recommendations + current.destinationComparison).first { it.placeId == placeId }
        group = group.copy(
            phase = GroupPhase.EN_ROUTE,
            selectedMeetingPointId = placeId,
            meetingPoint = point,
            version = group.version + 1,
        )
        startTimeline(point, Instant.parse(current.computedAt))
        return group
    }

    private fun startTimeline(point: MeetingCandidate, startedAt: Instant) {
        timeline?.cancel()
        val travelSeconds = point.arrivalEta.mapValues { Duration.between(startedAt, Instant.parse(it.value)).seconds.toDouble() }
        timeline = scope.launch {
            var elapsed = 0.0
            while (isActive && group.phase == GroupPhase.EN_ROUTE) {
                delay(tickMillis)
                elapsed += tickMillis / 1000.0 * timeScale
                advance(point, travelSeconds, elapsed)
            }
        }
    }

    private fun advance(point: MeetingCandidate, travelSeconds: Map<String, Double>, elapsed: Double) {
        val now = clock().toString()
        val moved = group.participants.map { participant ->
            val total = travelSeconds.getValue(participant.vehicleId)
            val start = scenario.vehicles.first { it.vehicleId == participant.vehicleId }
            val ratio = if (total <= 0) 1.0 else (elapsed / total).coerceAtMost(1.0)
            // 녹화본의 후보는 모두 좌표가 있다. 없으면 출발지에 머문다.
            val targetLat = point.latitude ?: start.lat
            val targetLon = point.longitude ?: start.lon
            participant.copy(
                lat = start.lat + (targetLat - start.lat) * ratio,
                lon = start.lon + (targetLon - start.lon) * ratio,
                timestamp = now,
                etaSeconds = (total - elapsed).coerceAtLeast(0.0),
                etaSource = "ROAD_ROUTE",
                etaUpdatedAt = now,
                arrived = ratio >= 1.0,
            )
        }
        val allArrived = moved.all { it.arrived }
        group = group.copy(
            participants = moved,
            phase = if (allArrived) GroupPhase.ALL_ARRIVED else group.phase,
            version = group.version + 1,
        )
        publishGroup()
    }

    private fun publishGroup() {
        eventFlow.tryEmit(SessionEvent.GroupUpdated(group))
    }

    override suspend fun leave() = close()

    override suspend fun complete() {
        eventFlow.tryEmit(SessionEvent.SessionClosed("COMPLETED"))
        close()
    }

    override fun close() {
        timeline?.cancel()
    }
}
