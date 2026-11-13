package com.meetroute.app.navigation

import com.meetroute.app.domain.NavProgress
import com.meetroute.app.domain.distanceMeters
import kotlinx.coroutines.flow.StateFlow

// 내비는 좌표를 도로에 맞춰 돌려줄 수 있어 약간의 차이는 같은 장소로 본다.
private const val SAME_PLACE_METERS = 300.0

// 같은 이름이면 내비와 앱의 기준 좌표가 달라도 같은 장소다. 같은 이름의 먼 지점은 다른 장소로 본다.
private const val SAME_NAME_METERS = 5_000.0

data class NavigationTarget(
    val name: String,
    val lat: Double,
    val lon: Double,
    /** MeetRoute 장소 식별자. 내비의 값이 어느 합류 지점의 것인지 서버에 밝힐 때 쓴다. */
    val placeId: String? = null,
    val poiId: String = "",
    val poiSubId: String = "",
    val address: String = "",
)

fun NavigationTarget.isSamePlace(other: NavigationTarget): Boolean {
    val distance = distanceMeters(lat, lon, other.lat, other.lon)
    return distance <= SAME_PLACE_METERS || (name.isNotBlank() && name == other.name && distance <= SAME_NAME_METERS)
}

/** Pleos 내비는 경유지 조회에 좌표를 0.0으로 답한다. 값이 없는 것으로 다룬다. */
private fun WaypointSnapshot.position(): Pair<Double, Double>? {
    val latitude = lat ?: return null
    val longitude = lon ?: return null
    return (latitude to longitude).takeUnless { latitude == 0.0 && longitude == 0.0 }
}

fun WaypointSnapshot.matches(target: NavigationTarget): Boolean {
    if (name == target.name) return true
    val (latitude, longitude) = position() ?: return false
    return distanceMeters(latitude, longitude, target.lat, target.lon) <= SAME_PLACE_METERS
}

fun WaypointSnapshot.toTarget(): NavigationTarget? {
    val (latitude, longitude) = position() ?: return null
    return NavigationTarget(name, latitude, longitude, poiId = poiId, poiSubId = poiSubId, address = address)
}

data class VehiclePosition(val lat: Double, val lon: Double, val heading: Double?)

sealed interface NavigationResult {
    /** 내비의 실제 상태를 조회해 반영을 확인한 경우만 성공이다. */
    data object Applied : NavigationResult

    /** 실제 내비게이션이 없어 요청만 기록했다. 성공으로 표시하면 안 된다. */
    data object RecordedOnly : NavigationResult

    /** 내비가 탐색을 끝냈다고 알렸지만 조회로 반영을 확인하지 못했다. 성공으로 표시하면 안 된다. */
    data object Unconfirmed : NavigationResult
    data class Failed(val reason: String) : NavigationResult
    data object TimedOut : NavigationResult
    data object Unavailable : NavigationResult

    /** 안내 중인 목적지가 그룹 목적지와 다르다. 바꾸기 전에 운전자에게 물어야 한다. */
    data class DestinationMismatch(val current: NavigationTarget, val requested: NavigationTarget) : NavigationResult
}

/** 내비에 넣은 합류 지점의 기억. 앱을 다시 켜도 이전 합류 지점을 찾아 지울 수 있게 한다. */
interface AppliedPointStore {
    var meetingPoint: NavigationTarget?
}

class InMemoryAppliedPointStore : AppliedPointStore {
    override var meetingPoint: NavigationTarget? = null
}

/** 내비게이션 연동 경계. 요청 호출이 아니라 내비의 실제 상태로 결과를 확정한다(design.md §9.2). */
interface NavigationGateway {
    /** 지금 등록한 합류 지점의 진행 상황. 추적 중인 대상이 없으면 null이다. */
    val progress: StateFlow<NavProgress?>

    /** 시연 화면에서 실제 내비게이션과 기록용 구현을 구분해 표시한다. */
    val label: String

    val supportsVehicleLocation: Boolean

    /** 지금 연결을 시도한다. */
    suspend fun connect(): Boolean

    /** 끊겨 있으면 재시도 간격을 지켜 연결한다. 위치 수집처럼 오래 기다리면 안 되는 곳에서 쓴다. */
    suspend fun ensureConnected(): Boolean = connect()

    /**
     * 합류 장소를 경유지로, 공통 목적지를 최종 목적지로 설정한다.
     *
     * 다른 목적지로 안내 중이면 [NavigationResult.DestinationMismatch]를 돌려주고 아무것도 바꾸지 않는다.
     * 운전자가 확인한 뒤 replaceDestination=true로 다시 호출하면 목적지를 바꾼다.
     * 내비가 좌표를 알려 주는 운전자 경유지만 유지할 수 있다.
     */
    suspend fun applyMeetingPoint(
        meetingPoint: NavigationTarget,
        finalDestination: NavigationTarget,
        replaceDestination: Boolean = false,
    ): NavigationResult

    /** 공통 목적지에서 합류할 때는 경유지 없이 목적지만 설정한다. */
    suspend fun applyDestination(destination: NavigationTarget, replaceDestination: Boolean = false): NavigationResult

    /** 경로 안내 중일 때만 동작한다. */
    suspend fun reroute(): NavigationResult

    /** 내비게이션 화면을 앞으로 가져온다. 요청을 보내지 못하면 false다. */
    fun openNavigationScreen(): Boolean

    /** 내비의 장소 검색 화면을 연다. 요청을 보내지 못하면 false다. 내비는 빈 키워드를 무시한다. */
    fun openPlaceSearch(keyword: String): Boolean

    /** 내비에 설정된 목적지를 읽는다. 목적지가 없으면 null이고, 내비가 답하지 않으면 실패다. */
    suspend fun lookupDestination(): Result<NavigationTarget?>

    suspend fun currentDestination(): NavigationTarget? = lookupDestination().getOrNull()
    suspend fun currentLocation(): VehiclePosition?
    fun refreshProgress()

    /** 합류 계획이 바뀌면 이전 대상의 ETA·도착 상태를 버린다. 내비에 넣은 경유지는 건드리지 않는다. */
    fun resetProgress()
    fun release()
}
