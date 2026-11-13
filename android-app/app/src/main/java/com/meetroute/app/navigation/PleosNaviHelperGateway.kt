package com.meetroute.app.navigation

import com.meetroute.app.domain.NavProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

// SDK가 첫 연결을 500ms 간격으로 30번 시도하므로 그보다 길어야 onFailed를 받을 수 있다.
private const val CONNECT_TIMEOUT_MILLIS = 20_000L
private const val QUERY_TIMEOUT_MILLIS = 3_000L

// Pleos 에뮬레이터에서 경로 반영에 19~29초가 걸렸다.
private const val APPLY_TIMEOUT_MILLIS = 60_000L
private const val VERIFY_INTERVAL_MILLIS = 2_000L
private const val SEARCH_GRACE_MILLIS = 8_000L

// 내비는 경유지를 바꿀 때 경로를 취소했다가 다시 만든다. 이 시간이 지나도 안내가 없으면 끝난 것으로 본다.
private const val ROUTE_RESTORE_GRACE_MILLIS = 60_000L
private const val MAX_WAYPOINTS = 2

private const val STATE_UNKNOWN = "내비게이션 상태를 확인하지 못했습니다. 잠시 뒤 다시 시도해 주세요."
private const val WAYPOINTS_FULL = "내비게이션 경유지가 가득 찼습니다. 경유지를 하나 지운 뒤 다시 시도해 주세요."
private const val REMOVE_FAILED = "이전 합류 지점을 내비에서 지우지 못했습니다. 내비 화면에서 확인해 주세요."

private val ERROR_MESSAGES = mapOf(
    "CAUSE_LOW_BATTERY" to "배터리가 부족해 경로를 설정할 수 없습니다.",
    "CAUSE_SEARCH_ROUTE_OVER_DISTANCE" to "경로 탐색 가능 거리를 벗어났습니다.",
    "CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE" to "목적지가 너무 가깝습니다.",
    "CAUSE_SEARCH_ROUTE" to "경로 탐색 서버 오류입니다.",
)

/** 진행 중인 요청 하나의 상태. 콜백은 요청과 짝이 없어 신호로만 쓴다. */
private class ApplyAttempt {
    val signals = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    var searching = false

    @Volatile
    var searchCompleted = false

    @Volatile
    var error: String? = null

    fun signal() {
        signals.trySend(Unit)
    }
}

private data class TrackedTarget(val target: NavigationTarget, val isDestination: Boolean)

/**
 * NaviHelper 연동.
 *
 * SDK는 요청의 결과를 돌려주지 않고, 어떤 콜백이 오는지도 내비 앱의 구현과 상태에 따라 다르다.
 * 그래서 콜백은 다시 확인하라는 신호로만 쓰고 성공은 목적지·경유지를 조회해 판단한다.
 * 경유지는 번호가 밀리고 조회에 좌표가 없어 슬롯이 아니라 이름으로 찾는다.
 *
 * Pleos 내비는 경유지를 바꿀 때 경로를 취소했다가 다시 만든다. 그 사이 20~30초 동안 안내 상태가 꺼지고
 * 목적지와 경유지가 빈 값으로 온다. onRouteEnded도 탐색이 끝날 때마다 온다. 그래서 이 콜백들만으로
 * 안내가 끝났다고 보지 않고, 안내가 돌아오지 않을 때만 추적을 멈춘다(docs/sdk-integration.md §5).
 */
class PleosNaviHelperGateway(
    private val portFactory: () -> NaviHelperPort,
    private val appliedPoints: AppliedPointStore = InMemoryAppliedPointStore(),
    private val connectTimeoutMillis: Long = CONNECT_TIMEOUT_MILLIS,
    private val queryTimeoutMillis: Long = QUERY_TIMEOUT_MILLIS,
    private val applyTimeoutMillis: Long = APPLY_TIMEOUT_MILLIS,
    private val verifyIntervalMillis: Long = VERIFY_INTERVAL_MILLIS,
    private val searchGraceMillis: Long = SEARCH_GRACE_MILLIS,
    private val routeRestoreGraceMillis: Long = ROUTE_RESTORE_GRACE_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) : NavigationGateway {

    override val label = "Pleos NaviHelper"

    private val _progress = MutableStateFlow<NavProgress?>(null)
    override val progress: StateFlow<NavProgress?> = _progress.asStateFlow()

    private val connectMutex = Mutex()
    private val applyMutex = Mutex()
    private val queryMutex = Mutex()
    private var port: NaviHelperPort? = null

    private val pendingApply = AtomicReference<ApplyAttempt?>()
    private val pendingRouteState = AtomicReference<CompletableDeferred<Boolean>?>()
    private val pendingWaypoints = AtomicReference<CompletableDeferred<List<WaypointSnapshot>>?>()
    private val pendingDestination = AtomicReference<CompletableDeferred<DestinationSnapshot>?>()
    private val pendingLocation = AtomicReference<CompletableDeferred<VehiclePosition>?>()

    @Volatile
    private var tracked: TrackedTarget? = null

    @Volatile
    private var trackedSlot: WaypointSlot? = null

    /** 안내가 꺼진 시각. 다시 켜지면 지운다. */
    @Volatile
    private var interruptedAt: Long? = null

    @Volatile
    private var connected = false

    override val supportsVehicleLocation: Boolean get() = connected

    private val events = object : NaviHelperEvents {
        override fun onRouteState(guiding: Boolean) {
            pendingRouteState.getAndSet(null)?.complete(guiding)
            settleInterruption(guiding)
        }

        override fun onWaypoints(waypoints: List<WaypointSnapshot>) {
            pendingWaypoints.getAndSet(null)?.complete(waypoints)
            updateWaypointProgress(waypoints)
        }

        override fun onDestination(destination: DestinationSnapshot) {
            pendingDestination.getAndSet(null)?.complete(destination)
            updateDestinationProgress(destination)
        }

        override fun onCurrentLocation(position: VehiclePosition) {
            pendingLocation.getAndSet(null)?.complete(position)
        }

        // 요청 직후가 아니라 경로를 다시 만든 뒤에 온다.
        override fun onWaypointChanged() = finishSearch()

        override fun onDestinationChanged() = signal()

        // 탐색 시작일 뿐이다. 이후 탐색이 실패할 수 있어 성공으로 보지 않는다.
        override fun onRouteStarted() {
            pendingApply.get()?.apply {
                searching = true
                signal()
            }
        }

        override fun onRoutingCompleted() = finishSearch()

        override fun onReRouteEnded() = finishSearch()

        override fun onRouteCancelled() {
            markInterrupted()
            signal()
        }

        // 안내가 끝날 때가 아니라 탐색이 끝날 때마다 온다. onRoutingCompleted는 Pleos에서 오지 않았다.
        override fun onRouteEnded() = finishSearch()

        override fun onGuidanceChanged(guiding: Boolean) {
            if (guiding) interruptedAt = null else markInterrupted()
            signal()
        }

        override fun onWaypointArrived(name: String, slot: WaypointSlot?) {
            val current = tracked ?: return
            if (current.isDestination) return
            // 운전자의 다른 경유지 도착을 합류 도착으로 세지 않는다.
            if (name == current.target.name || (slot != null && slot == trackedSlot)) markArrived()
        }

        override fun onDestinationArrived(name: String) {
            if (tracked?.isDestination == true) markArrived()
        }

        override fun onError(code: String) {
            pendingApply.get()?.apply {
                error = ERROR_MESSAGES[code] ?: "내비게이션 오류: $code"
                signal()
            }
        }
    }

    private fun signal() {
        pendingApply.get()?.signal()
    }

    private fun finishSearch() {
        pendingApply.get()?.apply {
            searching = false
            searchCompleted = true
            signal()
        }
    }

    private fun markArrived() {
        _progress.update { it?.copy(arrived = true, remainSeconds = 0, remainMeters = 0) }
    }

    private fun markInterrupted() {
        if (tracked != null && interruptedAt == null) interruptedAt = clock()
    }

    private fun settleInterruption(guiding: Boolean) {
        val since = interruptedAt ?: return
        when {
            guiding -> interruptedAt = null
            clock() - since >= routeRestoreGraceMillis -> stopTracking()
        }
    }

    /** 안내가 돌아오지 않았다. 이미 도착했다면 그 사실은 남긴다. */
    private fun stopTracking() {
        interruptedAt = null
        appliedPoints.meetingPoint = null
        if (_progress.value?.arrived != true) resetProgress()
    }

    private fun updateWaypointProgress(waypoints: List<WaypointSnapshot>) {
        val current = tracked ?: return
        if (current.isDestination) return
        val index = waypoints.indexOfFirst { it.matches(current.target) }
        trackedSlot = WaypointSlot.entries.getOrNull(index)
        val match = waypoints.getOrNull(index)
        updateRemaining(match?.remainSeconds, match?.remainMeters)
    }

    private fun updateDestinationProgress(destination: DestinationSnapshot) {
        val current = tracked ?: return
        if (!current.isDestination) return
        val same = destination.target.isSamePlace(current.target)
        updateRemaining(destination.remainSeconds.takeIf { same }, destination.remainMeters.takeIf { same })
    }

    private fun updateRemaining(seconds: Int?, meters: Int?) {
        _progress.update { progress ->
            if (progress == null || progress.arrived) {
                progress
            } else {
                // remainTime은 초, remainDistance는 미터다(Pleos 에뮬레이터 실측).
                progress.copy(remainSeconds = seconds?.takeIf { it >= 0 }, remainMeters = meters?.takeIf { it >= 0 })
            }
        }
    }

    private fun track(destination: NavigationTarget, meetingPoint: NavigationTarget?) {
        appliedPoints.meetingPoint = meetingPoint
        val target = meetingPoint ?: destination
        tracked = TrackedTarget(target, isDestination = meetingPoint == null)
        trackedSlot = null
        interruptedAt = null
        _progress.value = NavProgress(targetId = target.placeId)
    }

    override suspend fun connect(): Boolean = connectMutex.withLock {
        if (connected) return true
        val outcome = CompletableDeferred<Boolean>()
        val created = runCatching(portFactory).getOrNull() ?: return false
        runCatching {
            created.initialize(events, onConnected = { outcome.complete(true) }, onFailed = { outcome.complete(false) })
        }.onFailure { outcome.complete(false) }
        connected = withTimeoutOrNull(connectTimeoutMillis) { outcome.await() } == true
        if (connected) {
            port = created
            resumeTracking()
        } else {
            // 다시 연결할 때 새로 만드므로 실패한 연결이 뒤늦게 살아나지 않도록 정리한다.
            runCatching(created::release)
        }
        connected
    }

    /** 앱을 다시 켜도 내비에 남아 있는 합류 지점의 진행을 이어서 본다. */
    private fun resumeTracking() {
        val point = appliedPoints.meetingPoint ?: return
        if (tracked == null) track(destination = point, meetingPoint = point)
    }

    override suspend fun applyMeetingPoint(
        meetingPoint: NavigationTarget,
        finalDestination: NavigationTarget,
        replaceDestination: Boolean,
    ): NavigationResult = applyMutex.withLock {
        val sdk = connectedPort() ?: return NavigationResult.Unavailable
        applyPlan(sdk, finalDestination, meetingPoint, replaceDestination)
    }

    override suspend fun applyDestination(
        destination: NavigationTarget,
        replaceDestination: Boolean,
    ): NavigationResult = applyMutex.withLock {
        val sdk = connectedPort() ?: return NavigationResult.Unavailable
        applyPlan(sdk, destination, null, replaceDestination)
    }

    private suspend fun applyPlan(
        sdk: NaviHelperPort,
        destination: NavigationTarget,
        meetingPoint: NavigationTarget?,
        replaceDestination: Boolean,
    ): NavigationResult {
        // 조회에 실패한 것을 안내 중이 아닌 것으로 보면 진행 중인 경로를 덮어쓴다.
        val guiding = awaitReply(pendingRouteState, sdk::requestRouteState) ?: return failedState()
        if (!guiding) return startRoute(sdk, destination, listOfNotNull(meetingPoint), meetingPoint)

        val waypoints = awaitReply(pendingWaypoints, sdk::requestWaypoints) ?: return failedState()
        val current = awaitReply(pendingDestination, sdk::requestDestination)?.target ?: return failedState()
        if (current.isSamePlace(destination)) return updateWaypoints(sdk, destination, waypoints, meetingPoint)
        if (!replaceDestination) return NavigationResult.DestinationMismatch(current, destination)

        // 목적지를 바꾸면 경유지가 사라지므로 운전자가 넣은 경유지를 함께 다시 요청한다.
        // 좌표를 모르는 경유지는 다시 넣을 수 없다. 화면이 지워질 수 있다고 미리 알린다.
        val previous = appliedPoints.meetingPoint
        val kept = waypoints
            .filterNot { waypoint -> listOfNotNull(previous, meetingPoint).any(waypoint::matches) }
            .mapNotNull(WaypointSnapshot::toTarget)
        val wanted = kept + listOfNotNull(meetingPoint)
        if (wanted.size > MAX_WAYPOINTS) return NavigationResult.Failed(WAYPOINTS_FULL)
        return startRoute(sdk, destination, wanted, meetingPoint)
    }

    private fun failedState() = NavigationResult.Failed(STATE_UNKNOWN)

    private suspend fun startRoute(
        sdk: NaviHelperPort,
        destination: NavigationTarget,
        waypoints: List<NavigationTarget>,
        meetingPoint: NavigationTarget?,
    ): NavigationResult {
        val request = {
            if (waypoints.isEmpty()) sdk.requestRoute(destination) else sdk.requestRouteWithWaypoints(destination, waypoints)
        }
        val result = awaitConfirmed(request) { planIsActive(sdk, destination, meetingPoint) }
        if (result == NavigationResult.Applied || result == NavigationResult.Unconfirmed) track(destination, meetingPoint)
        return result
    }

    /** 같은 목적지로 안내 중일 때 합류 경유지만 맞춘다. 운전자가 넣은 경유지는 건드리지 않는다. */
    private suspend fun updateWaypoints(
        sdk: NaviHelperPort,
        destination: NavigationTarget,
        waypoints: List<WaypointSnapshot>,
        meetingPoint: NavigationTarget?,
    ): NavigationResult {
        if (meetingPoint != null && waypoints.any { it.matches(meetingPoint) }) {
            track(destination, meetingPoint)
            return NavigationResult.Applied
        }

        var remaining = waypoints
        val previous = appliedPoints.meetingPoint
        val previousIndex = previous?.let { point -> waypoints.indexOfFirst { it.matches(point) } } ?: -1
        val previousSlot = WaypointSlot.entries.getOrNull(previousIndex)
        if (previous != null && previousSlot != null) {
            // 안내 상태는 재탐색이 끝나기 5초쯤 전에 돌아온다. 그때 추가를 보내면 탐색과 겹치므로 완료 콜백까지 기다린다.
            val removed = awaitConfirmed({ sdk.removeWaypoint(previousSlot) }) { attempt ->
                attempt.searchCompleted && waypointIsGone(sdk, previous) == true
            }
            if (removed != NavigationResult.Applied) {
                return if (removed is NavigationResult.Failed) removed else NavigationResult.Failed(REMOVE_FAILED)
            }
            remaining = awaitReply(pendingWaypoints, sdk::requestWaypoints) ?: return failedState()
        }
        appliedPoints.meetingPoint = null

        if (meetingPoint == null) {
            track(destination, null)
            return NavigationResult.Applied
        }
        if (remaining.size >= MAX_WAYPOINTS) return NavigationResult.Failed(WAYPOINTS_FULL)
        val slot = if (remaining.isEmpty()) WaypointSlot.FIRST else WaypointSlot.SECOND
        val result = awaitConfirmed({ sdk.addWaypoint(meetingPoint, slot) }) {
            planIsActive(sdk, destination, meetingPoint)
        }
        if (result == NavigationResult.Applied || result == NavigationResult.Unconfirmed) track(destination, meetingPoint)
        return result
    }

    /** 내비가 알려 준 현재 상태가 원하는 계획과 같은지 본다. 조회에 실패하면 null이다. */
    private suspend fun planIsActive(
        sdk: NaviHelperPort,
        destination: NavigationTarget,
        meetingPoint: NavigationTarget?,
    ): Boolean? {
        val current = awaitReply(pendingDestination, sdk::requestDestination)?.target ?: return null
        if (!current.isSamePlace(destination)) return false
        if (meetingPoint == null) return true
        val waypoints = awaitReply(pendingWaypoints, sdk::requestWaypoints) ?: return null
        return waypoints.any { it.matches(meetingPoint) }
    }

    private suspend fun waypointIsGone(sdk: NaviHelperPort, point: NavigationTarget): Boolean? {
        // 경로를 다시 만드는 동안에는 경유지가 빈 목록으로 온다. 안내가 돌아온 뒤의 목록만 믿는다.
        val guiding = awaitReply(pendingRouteState, sdk::requestRouteState) ?: return null
        if (!guiding) return false
        val waypoints = awaitReply(pendingWaypoints, sdk::requestWaypoints) ?: return null
        return waypoints.none { it.matches(point) }
    }

    override suspend fun reroute(): NavigationResult = applyMutex.withLock {
        val sdk = connectedPort() ?: return NavigationResult.Unavailable
        // Pleos 내비는 경로를 벗어나지 않았으면 재탐색 요청에 아무 콜백도 보내지 않는다(실측).
        // 조회로 구분되는 상태도 없어 요청만 보내고, 확인하지 못했다고 알린다.
        runCatching(sdk::requestReRoute).fold(
            onSuccess = { NavigationResult.Unconfirmed },
            onFailure = { NavigationResult.Failed(it.message ?: "내비게이션 요청에 실패했습니다.") },
        )
    }

    override fun openNavigationScreen(): Boolean {
        val sdk = port ?: return false
        return runCatching(sdk::openNavigationScreen).isSuccess
    }

    override fun openPlaceSearch(keyword: String): Boolean {
        val sdk = port ?: return false
        return runCatching { sdk.openPlaceSearch(keyword) }.isSuccess
    }

    override suspend fun lookupDestination(): Result<NavigationTarget?> {
        val sdk = connectedPort() ?: return Result.failure(IllegalStateException("내비 연결 없음"))
        val reply = awaitReply(pendingDestination, sdk::requestDestination)
            ?: return Result.failure(IllegalStateException("내비 응답 없음"))
        // 목적지가 없으면 빈 값으로 응답한다. 경로를 다시 만드는 동안도 같다.
        return Result.success(reply.target.takeUnless { it.name.isBlank() || (it.lat == 0.0 && it.lon == 0.0) })
    }

    override suspend fun currentLocation(): VehiclePosition? {
        val sdk = connectedPort() ?: return null
        return awaitReply(pendingLocation, sdk::requestCurrentLocation)
    }

    override fun refreshProgress() {
        val current = tracked ?: return
        port?.let { sdk ->
            runCatching {
                if (interruptedAt != null) sdk.requestRouteState()
                if (current.isDestination) sdk.requestDestination() else sdk.requestWaypoints()
            }
        }
    }

    override fun resetProgress() {
        tracked = null
        trackedSlot = null
        interruptedAt = null
        _progress.value = null
    }

    override fun release() {
        port?.let { runCatching(it::release) }
        port = null
        connected = false
        resetProgress()
    }

    private suspend fun connectedPort(): NaviHelperPort? = if (connect()) port else null

    private suspend fun awaitConfirmed(
        request: () -> Unit,
        confirmed: suspend (ApplyAttempt) -> Boolean?,
    ): NavigationResult {
        val attempt = ApplyAttempt()
        pendingApply.set(attempt)
        try {
            val sent = runCatching(request)
            if (sent.isFailure) {
                return NavigationResult.Failed(sent.exceptionOrNull()?.message ?: "내비게이션 요청에 실패했습니다.")
            }
            val outcome = withTimeoutOrNull(applyTimeoutMillis) { watch(attempt, confirmed) }
            return outcome ?: if (attempt.searchCompleted) NavigationResult.Unconfirmed else NavigationResult.TimedOut
        } finally {
            pendingApply.compareAndSet(attempt, null)
        }
    }

    /** 콜백이 오면 바로, 안 와도 주기적으로 실제 상태를 확인한다. */
    private suspend fun watch(attempt: ApplyAttempt, confirmed: suspend (ApplyAttempt) -> Boolean?): NavigationResult {
        var waited = 0L
        while (true) {
            val signalled = withTimeoutOrNull(verifyIntervalMillis) { attempt.signals.receive() } != null
            if (!signalled) waited += verifyIntervalMillis
            attempt.error?.let { return NavigationResult.Failed(it) }
            // 탐색 완료 콜백이 오지 않는 구현에 대비해 일정 시간 뒤에는 상태만으로 판단한다.
            val stillSearching = attempt.searching && waited < searchGraceMillis
            if (!stillSearching && confirmed(attempt) == true) return NavigationResult.Applied
        }
    }

    private suspend fun <T> awaitReply(
        slot: AtomicReference<CompletableDeferred<T>?>,
        request: () -> Unit,
    ): T? = queryMutex.withLock {
        val reply = CompletableDeferred<T>()
        slot.set(reply)
        if (runCatching(request).isFailure) {
            slot.set(null)
            return null
        }
        withTimeoutOrNull(queryTimeoutMillis) { reply.await() }.also { slot.set(null) }
    }
}
