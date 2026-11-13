package com.meetroute.app.location

import kotlinx.coroutines.flow.Flow

enum class LocationSourceType(val wireName: String, val label: String) {
    PLEOS_FUSED("PLEOS_FUSED", "Pleos FusedLocation"),
    PLEOS_NAVI("PLEOS_NAVI", "Pleos NaviHelper"),
    ANDROID("ANDROID", "Android 위치(개발용)"),
    SIMULATED("SIMULATED", "시뮬레이션"),
}

data class LocationFix(
    val lat: Double,
    val lon: Double,
    val timestampMillis: Long,
    val heading: Double? = null,
    val accuracyMeters: Double? = null,
    val source: LocationSourceType,
    /** 보내기 직전에 차량 배터리 값을 붙인다. 위치 공급자는 채우지 않는다. */
    val batteryPercent: Double? = null,
)

/**
 * 위치가 바뀔 때만 알려 주는 공급자에서 마지막 위치를 다시 확인하는 주기.
 * 정차 전송 간격(STOPPED_INTERVAL_MILLIS)을 나누어떨어지게 해야 20초 STALE 기준을 넘지 않는다.
 */
const val STATIONARY_POLL_MILLIS = 5_000L

private const val MAX_FIX_AGE_MILLIS = 120_000L
private const val MAX_FIX_LEAD_MILLIS = 30_000L

// 서버의 서비스 지역과 같아야 한다(backend/app/services/groups.py).
private val SERVICE_AREA_LAT = 33.0..39.0
private val SERVICE_AREA_LON = 124.0..132.0

/** 위치를 못 잡은 공급자가 주는 (0,0)이나 에뮬레이터 기본 좌표는 서버가 거부한다. 값이 없는 것으로 다룬다. */
fun LocationFix.isInServiceArea(): Boolean = lat in SERVICE_AREA_LAT && lon in SERVICE_AREA_LON

/**
 * 측정 시각이 현재와 크게 다르면 시각 기준이 다른 값으로 보고 수신 시각을 쓴다.
 * SDK 문서에 기준이 없고, 서버는 너무 오래되거나 미래인 위치를 통째로 거부하기 때문이다.
 */
fun plausibleTimestamp(measuredMillis: Long, nowMillis: Long): Long =
    if (measuredMillis in (nowMillis - MAX_FIX_AGE_MILLIS)..(nowMillis + MAX_FIX_LEAD_MILLIS)) {
        measuredMillis
    } else {
        nowMillis
    }

/** 차량 위치 공급자. 구현을 교체해도 나머지 코드는 바뀌지 않는다(design.md §5.1). */
interface VehicleLocationSource {
    val type: LocationSourceType

    /** 현재 환경에서 쓸 수 있는지 알려 준다. 예외를 던지지 않는다. */
    fun isAvailable(): Boolean

    /**
     * 정차 중에도 주기적으로 위치를 내보내야 한다. 오랫동안 값이 없으면 고장으로 보고 다음 공급자로 넘어간다.
     * 수집을 취소하면 콜백 해제와 자원 정리까지 끝난다.
     */
    fun updates(): Flow<LocationFix>
}

/** design.md §4의 우선순위대로 사용할 위치 공급자를 고른다. */
class LocationSourceSelector(private val sources: List<VehicleLocationSource>) {

    fun availableInOrder(): List<VehicleLocationSource> = sources.filter { it.isAvailable() }
}
