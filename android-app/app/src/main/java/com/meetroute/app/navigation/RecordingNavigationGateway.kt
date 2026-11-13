package com.meetroute.app.navigation

import com.meetroute.app.domain.NavProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val RETRY_INTERVAL_MILLIS = 30_000L

/** 테스트와 일반 Android 환경용. 실제 내비게이션에 반영하지 않고 요청만 기록한다. */
class RecordingNavigationGateway : NavigationGateway {

    override val label = "내비 없음(기록만 함)"
    override val supportsVehicleLocation = false

    private val _progress = MutableStateFlow<NavProgress?>(null)
    override val progress: StateFlow<NavProgress?> = _progress

    val requests = mutableListOf<String>()

    override suspend fun connect(): Boolean = true

    override suspend fun applyMeetingPoint(
        meetingPoint: NavigationTarget,
        finalDestination: NavigationTarget,
        replaceDestination: Boolean,
    ): NavigationResult {
        requests += "경유지 ${meetingPoint.name} → 목적지 ${finalDestination.name}"
        return NavigationResult.RecordedOnly
    }

    override suspend fun applyDestination(destination: NavigationTarget, replaceDestination: Boolean): NavigationResult {
        requests += "목적지 ${destination.name}"
        return NavigationResult.RecordedOnly
    }

    override suspend fun reroute(): NavigationResult {
        requests += "재탐색"
        return NavigationResult.RecordedOnly
    }

    override fun openNavigationScreen(): Boolean = false

    override fun openPlaceSearch(keyword: String): Boolean = false

    override suspend fun lookupDestination(): Result<NavigationTarget?> = Result.success(null)

    override suspend fun currentLocation(): VehiclePosition? = null

    override fun refreshProgress() = Unit

    override fun resetProgress() = Unit

    override fun release() = Unit
}

/**
 * Pleos 내비게이션이 없는 환경에서도 앱이 멈추지 않도록 연결 실패 시 기록용 구현으로 넘어간다.
 *
 * 부팅 직후처럼 서비스가 늦게 뜨는 경우가 있어 실패한 뒤에도 다시 시도하고, 성공하면 실제 내비로 돌아간다.
 */
class FallbackNavigationGateway(
    private val primary: NavigationGateway,
    private val fallback: NavigationGateway,
    private val retryIntervalMillis: Long = RETRY_INTERVAL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) : NavigationGateway {

    @Volatile
    private var active: NavigationGateway = primary

    @Volatile
    private var lastAttemptAt: Long? = null

    override val label: String get() = active.label

    // 기록용 구현은 진행 정보가 없다. 같은 Flow를 계속 쓰므로 실제 내비로 돌아가도 다시 구독할 필요가 없다.
    override val progress: StateFlow<NavProgress?> get() = primary.progress

    override val supportsVehicleLocation: Boolean get() = active.supportsVehicleLocation

    override suspend fun connect(): Boolean {
        lastAttemptAt = clock()
        active = if (primary.connect()) primary else fallback
        return active.connect()
    }

    override suspend fun ensureConnected(): Boolean {
        if (active === primary && primary.supportsVehicleLocation) return true
        val last = lastAttemptAt
        return if (last == null || clock() - last >= retryIntervalMillis) connect() else active.connect()
    }

    override suspend fun applyMeetingPoint(
        meetingPoint: NavigationTarget,
        finalDestination: NavigationTarget,
        replaceDestination: Boolean,
    ): NavigationResult {
        ensureConnected()
        return active.applyMeetingPoint(meetingPoint, finalDestination, replaceDestination)
    }

    override suspend fun applyDestination(destination: NavigationTarget, replaceDestination: Boolean): NavigationResult {
        ensureConnected()
        return active.applyDestination(destination, replaceDestination)
    }

    override suspend fun reroute(): NavigationResult {
        ensureConnected()
        return active.reroute()
    }

    override fun openNavigationScreen(): Boolean = active.openNavigationScreen()

    override fun openPlaceSearch(keyword: String): Boolean = active.openPlaceSearch(keyword)

    override suspend fun lookupDestination(): Result<NavigationTarget?> {
        ensureConnected()
        return active.lookupDestination()
    }

    override suspend fun currentLocation(): VehiclePosition? = active.currentLocation()

    override fun refreshProgress() = active.refreshProgress()

    override fun resetProgress() = primary.resetProgress()

    override fun release() = active.release()
}
