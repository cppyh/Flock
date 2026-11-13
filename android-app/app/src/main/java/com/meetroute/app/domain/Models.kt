package com.meetroute.app.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MeetingPurpose(val label: String) {
    DEPART_TOGETHER("같이 출발"),
    REST("휴식"),
    MEAL("식사"),
    ANY("상관없음"),
}

@Serializable
enum class ParticipantStatus(val label: String) {
    ACTIVE("공유 중"),
    STALE("연결 지연"),
    PAUSED("공유 꺼짐"),
    NO_LOCATION("위치 대기"),
    LEFT("나감"),
}

@Serializable
enum class GroupPhase {
    FORMING,
    EN_ROUTE,
    ALL_ARRIVED,
    CLOSED,
}

@Serializable
data class Destination(val name: String, val lat: Double, val lon: Double)

/** 목적지 없는 드라이브의 테마. 서버 값과 같아야 한다(backend/app/integrations/drive_spots.py). */
@Serializable
enum class DriveTheme(val label: String) {
    SEA("바다"),
    FOREST("숲길"),
    NIGHT("야경"),
    LAKE("호수·강"),
    RANDOM("완전 랜덤"),
}

/** 목적지 없는 드라이브로 고른 코스. 비공개면 그룹장 외에는 region·description이 비어 온다. */
@Serializable
data class FreeDrive(
    @SerialName("plan_id") val planId: String? = null,
    val hours: Int,
    val theme: DriveTheme = DriveTheme.RANDOM,
    @SerialName("private") val isPrivate: Boolean = false,
    val revealed: Boolean = true,
    @SerialName("round_trip_minutes") val roundTripMinutes: Double? = null,
    @SerialName("one_way_minutes") val oneWayMinutes: Double? = null,
    @SerialName("tolerance_minutes") val toleranceMinutes: Double? = null,
    @SerialName("within_tolerance") val withinTolerance: Boolean? = null,
    val notice: String? = null,
    val region: String? = null,
    val description: String? = null,
)

/** 전기차 한 대의 충전 계획. 서버가 합류 후보마다 계산한다. */
@Serializable
data class EvPlan(
    @SerialName("battery_percent") val batteryPercent: Double,
    @SerialName("battery_at_arrival") val batteryAtArrival: Double,
    @SerialName("battery_at_destination") val batteryAtDestination: Double,
    @SerialName("needs_charge") val needsCharge: Boolean,
    @SerialName("charger_available") val chargerAvailable: Boolean,
    @SerialName("charge_minutes") val chargeMinutes: Double,
    @SerialName("covered_by_wait_minutes") val coveredByWaitMinutes: Double,
    @SerialName("extra_wait_minutes") val extraWaitMinutes: Double,
    @SerialName("bonus_percent") val bonusPercent: Double = 0.0,
)

@Serializable
data class Participant(
    @SerialName("vehicle_id") val vehicleId: String,
    val nickname: String,
    val role: String,
    @SerialName("is_simulated") val isSimulated: Boolean = false,
    val status: ParticipantStatus = ParticipantStatus.NO_LOCATION,
    val online: Boolean = false,
    @SerialName("location_sharing") val locationSharing: Boolean = false,
    val lat: Double? = null,
    val lon: Double? = null,
    @SerialName("location_source") val locationSource: String? = null,
    val timestamp: String? = null,
    /** 전기차가 공유한 배터리 잔량(%). 내연기관차나 읽지 못한 차량은 null이다. */
    @SerialName("battery_percent") val batteryPercent: Double? = null,
    @SerialName("eta_seconds") val etaSeconds: Double? = null,
    @SerialName("eta_source") val etaSource: String? = null,
    @SerialName("eta_updated_at") val etaUpdatedAt: String? = null,
    val delayed: Boolean = false,
    val arrived: Boolean = false,
) {
    val isLeader: Boolean get() = role == "LEADER"
}

@Serializable
data class MeetingCandidate(
    val rank: Int? = null,
    @SerialName("place_id") val placeId: String,
    val name: String,
    /** 비공개 드라이브의 목적지 후보는 그룹원에게 좌표 없이 온다. */
    val latitude: Double? = null,
    val longitude: Double? = null,
    val direction: String? = null,
    @SerialName("route_name") val routeName: String? = null,
    @SerialName("estimated_group_meeting_time") val meetingTime: String,
    @SerialName("per_vehicle_arrival_eta") val arrivalEta: Map<String, String> = emptyMap(),
    @SerialName("per_vehicle_detour_minutes") val detourMinutes: Map<String, Double> = emptyMap(),
    @SerialName("per_vehicle_wait_minutes") val waitMinutes: Map<String, Double> = emptyMap(),
    @SerialName("total_detour_minutes") val totalDetourMinutes: Double,
    @SerialName("total_wait_minutes") val totalWaitMinutes: Double,
    @SerialName("max_detour_minutes") val maxDetourMinutes: Double,
    @SerialName("max_wait_minutes") val maxWaitMinutes: Double,
    @SerialName("shared_driving_minutes") val sharedMinutes: Double,
    @SerialName("parking_spaces") val parkingSpaces: Int? = null,
    @SerialName("has_restaurant") val hasRestaurant: Boolean? = null,
    @SerialName("has_ev_charger") val hasEvCharger: Boolean? = null,
    @SerialName("data_date") val dataDate: String? = null,
    @SerialName("data_uncertain") val dataUncertain: Boolean = false,
    @SerialName("is_destination_fallback") val isDestination: Boolean = false,
    @SerialName("ev_plan") val evPlan: Map<String, EvPlan>? = null,
    @SerialName("ev_extra_minutes") val evExtraMinutes: Double = 0.0,
    val reason: String = "",
) {
    val hasCoordinates: Boolean get() = latitude != null && longitude != null
}

@Serializable
data class RecommendationSet(
    @SerialName("recommendation_id") val recommendationId: String,
    @SerialName("computed_at") val computedAt: String,
    val recommendations: List<MeetingCandidate>,
    @SerialName("destination_comparison") val destinationComparison: MeetingCandidate,
    val notice: String? = null,
    @SerialName("window_expanded") val windowExpanded: Boolean = false,
    @SerialName("change_recommended") val changeRecommended: Boolean? = null,
    @SerialName("route_source") val routeSource: String,
    @SerialName("traffic_aware") val trafficAware: Boolean = false,
    @SerialName("excluded_vehicles") val excludedVehicles: List<String> = emptyList(),
    val stale: Boolean = false,
)

@Serializable
data class GroupState(
    @SerialName("group_id") val groupId: String,
    val name: String,
    @SerialName("invite_code") val inviteCode: String,
    @SerialName("max_vehicles") val maxVehicles: Int = 3,
    val destination: Destination? = null,
    /** 비공개 드라이브라 이 차량에게 목적지가 가려져 있다. 모두 합류하면 공개된다. */
    @SerialName("destination_hidden") val destinationHidden: Boolean = false,
    @SerialName("free_drive") val freeDrive: FreeDrive? = null,
    @SerialName("meeting_purpose") val meetingPurpose: MeetingPurpose = MeetingPurpose.ANY,
    @SerialName("meal_time") val mealTime: String? = null,
    val phase: GroupPhase = GroupPhase.FORMING,
    val version: Int = 1,
    val participants: List<Participant> = emptyList(),
    @SerialName("selected_meeting_point_id") val selectedMeetingPointId: String? = null,
    @SerialName("meeting_point") val meetingPoint: MeetingCandidate? = null,
    @SerialName("reroute_suggested") val rerouteSuggested: Boolean = false,
) {
    /** 목적지가 정해졌는지. 비공개라 이 차량이 모르는 경우도 정해진 것이다. */
    val hasDestination: Boolean get() = destination != null || destinationHidden
}

@Serializable
data class Session(
    val group: GroupState,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("access_token") val accessToken: String,
)

enum class ConnectionState(val label: String) {
    OFFLINE("연결 안 됨"),
    CONNECTING("연결 중"),
    CONNECTED("연결됨"),
    RECONNECTING("재연결 중"),
    CLOSED("종료됨"),
}

sealed interface SessionEvent {
    data class ParticipantUpdated(val participant: Participant) : SessionEvent
    data class GroupUpdated(val group: GroupState) : SessionEvent
    data class RecommendationsReady(val recommendations: RecommendationSet) : SessionEvent

    /** 연결될 때마다 서버의 최신 추천으로 맞춘다. 추천이 없으면 null이다. 화면은 이동시키지 않는다. */
    data class RecommendationsSynced(val recommendations: RecommendationSet?) : SessionEvent
    data class SessionClosed(val reason: String) : SessionEvent
    data class ServerError(val code: String, val message: String) : SessionEvent
}

/** 서버가 알려 준 오류. code로 분기하고 message는 그대로 화면에 보여 준다. */
class MeetRouteException(
    val code: String,
    override val message: String,
    val lastGood: RecommendationSet? = null,
) : Exception(message)
