package com.meetroute.app.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meetroute.app.di.AppContainer
import com.meetroute.app.domain.ConnectionState
import com.meetroute.app.domain.Destination
import com.meetroute.app.domain.DriveTheme
import com.meetroute.app.domain.GroupAnnouncer
import com.meetroute.app.domain.GroupPhase
import com.meetroute.app.domain.GroupReducer
import com.meetroute.app.domain.GroupState
import com.meetroute.app.domain.MeetPointRepository
import com.meetroute.app.domain.MeetRouteException
import com.meetroute.app.domain.MeetingCandidate
import com.meetroute.app.domain.MeetingPurpose
import com.meetroute.app.domain.RecommendationSet
import com.meetroute.app.domain.Session
import com.meetroute.app.domain.SessionEvent
import com.meetroute.app.location.LocationSourceType
import com.meetroute.app.navigation.NavigationResult
import com.meetroute.app.navigation.NavigationTarget
import com.meetroute.app.navigation.awaitPickedDestination
import com.meetroute.app.navigation.toDestination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import java.time.Instant

enum class Screen { HOME, CREATE, JOIN, LOBBY, RECOMMENDATIONS, DRIVING, COMPLETED }

sealed interface NavigationUiState {
    data object Idle : NavigationUiState
    data object Requesting : NavigationUiState
    data object Applied : NavigationUiState
    data object RecordedOnly : NavigationUiState

    /** 내비가 탐색을 끝냈다고 했지만 반영을 조회로 확인하지 못했다. */
    data object Unconfirmed : NavigationUiState

    /** 다른 목적지로 안내 중이다. 운전자가 고르기 전에는 내비를 바꾸지 않는다. */
    data class ConfirmDestination(val current: String, val requested: String) : NavigationUiState
    data class Failed(val message: String) : NavigationUiState
}

data class MeetRouteUiState(
    val screen: Screen = Screen.HOME,
    val group: GroupState? = null,
    val myVehicleId: String? = null,
    val recommendations: RecommendationSet? = null,
    val connection: ConnectionState = ConnectionState.OFFLINE,
    val dataLabel: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val locationSource: LocationSourceType? = null,
    val locationProblem: String? = null,
    val sharingNotice: String? = null,
    val isDriving: Boolean = false,
    val navigationLabel: String = "",
    val navigationConnected: Boolean = false,
    val navigation: NavigationUiState = NavigationUiState.Idle,
    val navRemainSeconds: Int? = null,

    /** 내비 검색 화면에서 장소를 고르기를 기다리는 검색어. */
    val destinationSearch: String? = null,
    val voiceEnabled: Boolean = true,
    val voiceAvailable: Boolean = false,
    val serverUrl: String = "",
    val hasStoredSession: Boolean = false,
    val nowMillis: Long = 0,
) {
    val me get() = group?.participants?.find { it.vehicleId == myVehicleId }
    val isLeader get() = me?.isLeader == true
    val isSharing get() = me?.locationSharing == true
    val sessionOpen get() = group != null && group.phase != GroupPhase.CLOSED
}

private const val TAG = "MeetRoute"
private const val CLOCK_TICK_MILLIS = 1_000L
private const val NAVIGATION_CONNECT_ATTEMPTS = 5
private const val NAVIGATION_RETRY_MILLIS = 30_000L
private const val MEETING_POINT_CHANGED = "합류 지점이 바뀌었습니다. 내비에 다시 등록해 주세요."
private const val NAVIGATION_ROUTE_ENDED = "내비 안내가 끝났습니다. 필요하면 다시 등록해 주세요."

// 이 오류는 세션이 끝났다는 뜻이 아니므로 저장된 여행을 지우지 않는다.
private val RETRYABLE_RESUME_ERRORS = setOf("NETWORK", "INVALID_SERVER_URL")

// 이전 상태를 보고 선택했다는 뜻이다.
private val OUTDATED_SELECTION_ERRORS = setOf("VERSION_CONFLICT", "RECOMMENDATION_OUTDATED")

class MeetRouteViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(
        MeetRouteUiState(
            serverUrl = container.sessionStore.serverUrl,
            hasStoredSession = container.sessionStore.load() != null,
            nowMillis = System.currentTimeMillis(),
        )
    )
    val state: StateFlow<MeetRouteUiState> = _state.asStateFlow()

    private var sessionJobs: List<Job> = emptyList()
    private val announcer = GroupAnnouncer()
    private var voiceStarted = false
    private var navigationConnectJob: Job? = null
    private var destinationSearchJob: Job? = null

    // 운전자가 목적지 변경을 확인하면 다시 실행할 요청.
    private var pendingNavigation: (suspend (Boolean) -> NavigationResult)? = null

    // 서버에 알리지 못한 공유 철회. 연결되면 다시 보낸다.
    private var withdrawalPending = false

    private val repository: MeetPointRepository?
        get() = container.repository

    init {
        viewModelScope.launch {
            while (isActive) {
                val voiceAvailable = voiceStarted && container.voice.isAvailable
                _state.update { it.copy(nowMillis = System.currentTimeMillis(), voiceAvailable = voiceAvailable) }
                delay(CLOCK_TICK_MILLIS)
            }
        }
        val sharing = container.locationSharing
        viewModelScope.launch { sharing.activeSource.collect { v -> _state.update { it.copy(locationSource = v) } } }
        viewModelScope.launch { sharing.problem.collect { v -> _state.update { it.copy(locationProblem = v) } } }
        viewModelScope.launch { container.sharingNotice.collect { v -> _state.update { it.copy(sharingNotice = v) } } }
        // 연결 대기가 메인 스레드를 막지 않도록 앱 범위에서 시작한다.
        container.scope.launch { container.drivingRestriction.start() }
        viewModelScope.launch {
            // 차량이 알려 주는 상태를 먼저 쓴다. 알 수 없는 환경에서만 위치로 추정한 값을 쓴다.
            combine(container.drivingRestriction.restricted, sharing.isDriving) { restricted, moving ->
                restricted ?: moving
            }.collect { driving -> _state.update { it.copy(isDriving = driving) } }
        }
        viewModelScope.launch {
            container.navigation.progress.collect { progress ->
                _state.update { current ->
                    val routeLost = progress == null && current.navigation == NavigationUiState.Applied
                    current.copy(
                        navRemainSeconds = progress?.remainSeconds,
                        navigation = if (routeLost) NavigationUiState.Idle else current.navigation,
                        message = if (routeLost) NAVIGATION_ROUTE_ENDED else current.message,
                    )
                }
            }
        }
    }

    /** 내비 권한 처리가 끝난 뒤 부른다. 서비스가 늦게 뜨는 경우를 위해 제한된 횟수만큼 다시 시도한다. */
    fun connectNavigation() {
        if (navigationConnectJob?.isActive == true) return
        navigationConnectJob = viewModelScope.launch {
            repeat(NAVIGATION_CONNECT_ATTEMPTS) { attempt ->
                if (attempt > 0) delay(NAVIGATION_RETRY_MILLIS)
                container.navigation.connect()
                publishNavigationStatus()
                if (container.navigation.supportsVehicleLocation) return@launch
            }
        }
    }

    private fun publishNavigationStatus() {
        _state.update {
            it.copy(
                navigationLabel = container.navigation.label,
                navigationConnected = container.navigation.supportsVehicleLocation,
            )
        }
    }

    fun open(screen: Screen) = _state.update { it.copy(screen = screen, message = null) }

    fun reportMessage(message: String) = _state.update { it.copy(message = message) }

    fun updateServerUrl(url: String) {
        container.sessionStore.serverUrl = url.trim()
        _state.update { it.copy(serverUrl = container.sessionStore.serverUrl) }
    }

    fun createGroup(name: String, nickname: String) = perform {
        val repository = useRepository(container.serverRepository())
        beginSession(repository, repository.createGroup(name, nickname))
    }

    fun joinGroup(code: String, nickname: String) = perform {
        val repository = useRepository(container.serverRepository())
        beginSession(repository, repository.joinGroup(code, nickname))
    }

    fun startDemo() = perform {
        val repository = useRepository(container.demoRepository())
        val session = repository.createGroup("", "")
        observe(repository)
        _state.update {
            it.copy(
                group = session.group,
                myVehicleId = session.vehicleId,
                dataLabel = repository.dataLabel,
                screen = Screen.LOBBY,
            )
        }
    }

    fun resumeSession() = perform {
        val stored = container.sessionStore.load() ?: return@perform
        val repository = useRepository(container.serverRepository())
        val group = try {
            repository.resume(stored)
        } catch (error: MeetRouteException) {
            if (error.code !in RETRYABLE_RESUME_ERRORS) {
                container.sessionStore.clear()
                _state.update { it.copy(hasStoredSession = false) }
            }
            throw error
        }
        observe(repository)
        _state.update {
            it.copy(group = group, myVehicleId = stored.vehicleId, screen = screenFor(group, it.screen))
        }
        repository.connectRealtime()
        if (_state.value.isSharing) startSharing(repository)
    }

    private fun useRepository(next: MeetPointRepository): MeetPointRepository {
        // 이전 연결이 남으면 같은 차량의 새 연결을 밀어내며 재접속을 반복한다.
        container.repository?.close()
        container.repository = next
        return next
    }

    /** 구독과 화면 상태를 먼저 맞춘 뒤 채널을 열어야 그사이의 이벤트를 놓치지 않는다. */
    private fun beginSession(repository: MeetPointRepository, session: Session) {
        container.sessionStore.save(session.group.groupId, session.vehicleId, session.accessToken)
        observe(repository)
        _state.update {
            it.copy(
                group = session.group,
                myVehicleId = session.vehicleId,
                dataLabel = repository.dataLabel,
                hasStoredSession = true,
                screen = Screen.LOBBY,
            )
        }
        repository.connectRealtime()
    }

    private fun observe(repository: MeetPointRepository) {
        // 음성 엔진은 초기화에 시간이 걸려 첫 안내를 놓치지 않도록 미리 깨운다.
        container.voice
        voiceStarted = true
        sessionJobs.forEach(Job::cancel)
        sessionJobs = listOf(
            viewModelScope.launch { repository.events.collect(::onEvent) },
            viewModelScope.launch {
                repository.connection.collect { connection ->
                    _state.update { it.copy(connection = connection) }
                    if (connection == ConnectionState.CONNECTED && withdrawalPending) retryWithdrawal(repository)
                }
            },
        )
    }

    private fun onEvent(event: SessionEvent) {
        val previous = _state.value
        when (event) {
            is SessionEvent.RecommendationsReady -> _state.update {
                it.copy(recommendations = event.recommendations, screen = screenOnRecommendations(it.screen))
            }
            is SessionEvent.RecommendationsSynced -> applySyncedRecommendations(event.recommendations)
            is SessionEvent.ServerError -> _state.update { it.copy(message = event.message) }
            is SessionEvent.SessionClosed -> endSession(event)
            else -> Unit
        }
        val group = previous.group ?: return
        if (event is SessionEvent.SessionClosed) return
        val updated = GroupReducer.reduce(group, event)
        val planChanged = isPlanChanged(group, updated)
        val meetingChanged = group.selectedMeetingPointId != updated.selectedMeetingPointId
        val justArrived = updated.phase == GroupPhase.ALL_ARRIVED && group.phase != GroupPhase.ALL_ARRIVED
        _state.update {
            val wasOnNavigation = it.navigation != NavigationUiState.Idle
            it.copy(
                group = updated,
                recommendations = if (planChanged) null else it.recommendations,
                navigation = if (justArrived || planChanged || meetingChanged) NavigationUiState.Idle else it.navigation,
                message = if (meetingChanged && updated.meetingPoint != null && wasOnNavigation) {
                    MEETING_POINT_CHANGED
                } else {
                    it.message
                },
                screen = screenAfterUpdate(group, updated, it.screen),
            )
        }
        // 이전 합류 지점의 ETA·도착이 새 계획에 섞이지 않게 한다. 내비의 경유지는 운전자가 다시 등록한다.
        if (planChanged || meetingChanged) forgetNavigationPlan()
        speak(announcer.announcements(group, updated, previous.myVehicleId))
    }

    private fun applySyncedRecommendations(synced: RecommendationSet?) = _state.update {
        val current = synced?.takeUnless(RecommendationSet::stale)
        val lostScreen = it.screen == Screen.RECOMMENDATIONS && current == null
        it.copy(
            recommendations = current,
            screen = if (lostScreen) it.group?.let { group -> screenFor(group, Screen.LOBBY) } ?: Screen.HOME else it.screen,
        )
    }

    /**
     * 화면의 내비 상태를 먼저 바꾼 뒤에 부른다. 등록 상태에서 진행 정보가 사라지면
     * 내비 안내가 끝난 것으로 보고 운전자에게 알리기 때문이다.
     */
    private fun forgetNavigationPlan() {
        pendingNavigation = null
        container.navigation.resetProgress()
    }

    private fun speak(messages: List<String>) {
        if (!_state.value.voiceEnabled) return
        messages.forEach(container.voice::speak)
    }

    private fun endSession(event: SessionEvent.SessionClosed) {
        cancelDestinationSearch()
        stopSharing()
        container.sessionStore.clear()
        // 서버가 닫은 세션에 재접속을 반복하지 않도록 연결도 정리한다.
        container.repository?.close()
        _state.update {
            it.copy(
                group = it.group?.copy(phase = GroupPhase.CLOSED),
                hasStoredSession = false,
                navigation = NavigationUiState.Idle,
                screen = if (event.reason == "COMPLETED") Screen.COMPLETED else Screen.HOME,
            )
        }
        forgetNavigationPlan()
    }

    /** 공유는 사용자가 동의한 뒤에만 시작한다. 권한 요청은 화면에서 먼저 끝낸다. */
    fun setLocationSharing(enabled: Boolean) = perform {
        val repository = requireRepository()
        if (enabled) {
            withdrawalPending = false
            repository.setLocationSharing(true)
            startSharing(repository)
        } else {
            // 서버 응답과 상관없이 수집은 바로 멈춘다. 철회는 네트워크보다 앞서야 한다.
            stopSharing()
            withdrawSharing(repository)
        }
        val group = repository.refresh()
        _state.update { it.copy(group = group) }
    }

    private suspend fun withdrawSharing(repository: MeetPointRepository) {
        withdrawalPending = true
        try {
            repository.setLocationSharing(false)
            withdrawalPending = false
        } catch (error: MeetRouteException) {
            throw MeetRouteException(error.code, "위치 수집은 멈췄습니다. 서버에는 연결되면 다시 알립니다.")
        }
    }

    private fun retryWithdrawal(repository: MeetPointRepository) {
        viewModelScope.launch {
            runCatching {
                withdrawSharing(repository)
                val group = repository.refresh()
                _state.update { it.copy(group = group, message = null) }
            }
        }
    }

    private fun startSharing(repository: MeetPointRepository) {
        // 시연 모드는 녹화된 위치를 재생하므로 실제 위치를 읽지 않는다.
        if (repository.dataLabel != null) return
        container.startSharingService()
        container.locationSharing.start(repository)
    }

    private fun stopSharing() {
        container.locationSharing.stop()
        container.stopSharingService()
    }

    fun setDestination(destination: Destination) {
        cancelDestinationSearch()
        perform {
            val group = requireRepository().setDestination(destination)
            applyNewPlan(group)
        }
    }

    fun importNavigationDestination() {
        cancelDestinationSearch()
        perform {
            val target = container.navigation.currentDestination()
                ?: throw MeetRouteException("NO_NAV_DESTINATION", "내비게이션에 설정된 목적지가 없습니다.")
            val group = requireRepository().setDestination(target.toDestination())
            applyNewPlan(group)
        }
    }

    /** 내비의 장소 검색을 열고, 운전자가 고른 장소를 그룹 목적지로 가져온다. */
    fun searchDestination(keyword: String) {
        val query = keyword.trim()
        if (query.isEmpty() || destinationSearchJob?.isActive == true) return
        val navigation = container.navigation
        destinationSearchJob = viewModelScope.launch {
            _state.update { it.copy(destinationSearch = query, message = null) }
            try {
                // 검색 전 목적지를 모르면 이미 있던 목적지를 고른 장소로 착각할 수 있다.
                val before = navigation.lookupDestination().getOrElse {
                    reportMessage("내비게이션이 응답하지 않습니다. 잠시 뒤 다시 시도해 주세요.")
                    return@launch
                }
                if (!navigation.openPlaceSearch(query)) {
                    reportMessage("내비 검색을 열 수 없습니다. 내비 연결을 확인해 주세요.")
                    return@launch
                }
                val picked = awaitPickedDestination(navigation, before, canApply = ::canChangeDestination)
                if (picked == null) {
                    reportMessage("내비에서 고른 장소가 없어 검색을 끝냈습니다.")
                } else {
                    applyPickedDestination(picked)
                }
            } finally {
                _state.update { it.copy(destinationSearch = null) }
            }
        }
    }

    fun cancelDestinationSearch() {
        destinationSearchJob?.cancel()
    }

    // 주행 중이거나 그룹장이 대기실을 벗어나면 목적지를 바꾸지 않는다. 화면의 규칙과 같다.
    private fun canChangeDestination(): Boolean {
        val state = _state.value
        return !state.isDriving && state.isLeader && state.screen == Screen.LOBBY
    }

    private fun applyPickedDestination(target: NavigationTarget) = perform {
        val destination = target.toDestination()
        val group = requireRepository().setDestination(destination)
        applyNewPlan(group)
        _state.update { it.copy(message = "목적지를 ${destination.name}(으)로 정했습니다.") }
    }

    fun setPurpose(purpose: MeetingPurpose, mealOffsetMinutes: Long? = null) = perform {
        val mealTime = mealOffsetMinutes?.let { Instant.now().plusSeconds(it * 60).toString() }
        val group = requireRepository().setPurpose(purpose, mealTime)
        applyNewPlan(group)
    }

    /** 목적지 없는 드라이브. reroll이면 이번 드라이브에서 보여 준 곳을 빼고 다시 고른다. */
    fun startFreeDrive(hours: Int, theme: DriveTheme, privateDestination: Boolean, reroll: Boolean = false) {
        cancelDestinationSearch()
        perform {
            val group = requireRepository().startFreeDrive(hours, theme, privateDestination, reroll)
            applyNewPlan(group)
        }
    }

    private fun applyNewPlan(group: GroupState) {
        _state.update { it.copy(group = group, recommendations = null, navigation = NavigationUiState.Idle) }
        forgetNavigationPlan()
    }

    fun requestRecommendations() = perform {
        try {
            val result = requireRepository().requestRecommendations()
            _state.update { it.copy(recommendations = result, screen = Screen.RECOMMENDATIONS) }
        } catch (error: MeetRouteException) {
            // 경로 조회 실패 시 추정값 대신 마지막 정상 결과를 보여 준다.
            val lastGood = error.lastGood ?: throw error
            _state.update {
                it.copy(
                    recommendations = lastGood.copy(stale = true),
                    screen = Screen.RECOMMENDATIONS,
                    message = error.message,
                )
            }
        }
    }

    fun selectMeetingPoint(candidate: MeetingCandidate) = perform {
        val current = _state.value
        val recommendations = current.recommendations ?: return@perform
        val group = current.group ?: return@perform
        val repository = requireRepository()
        val updated = try {
            repository.selectMeetingPoint(recommendations.recommendationId, candidate.placeId, group.version)
        } catch (error: MeetRouteException) {
            if (error.code in OUTDATED_SELECTION_ERRORS) {
                val latest = repository.refresh()
                _state.update { it.copy(group = latest, recommendations = it.recommendations?.copy(stale = true)) }
            }
            throw error
        }
        _state.update {
            it.copy(group = updated, navigation = NavigationUiState.Idle, screen = screenFor(updated, Screen.DRIVING))
        }
        forgetNavigationPlan()
    }

    /** 운전자가 직접 누를 때만 내비게이션에 반영한다(기획서 §9.2). */
    fun applyToNavigation() {
        val group = _state.value.group ?: return
        val point = group.meetingPoint ?: return
        val navigation = container.navigation
        val destination = group.destination
        if (point.isDestination) {
            destination ?: return
            val target = NavigationTarget(destination.name, destination.lat, destination.lon, placeId = point.placeId)
            runNavigation { replace -> navigation.applyDestination(target, replace) }
            return
        }
        val meeting = navigationTarget(point) ?: return
        if (destination == null) {
            // 비공개 드라이브의 그룹원은 목적지를 모른다. 합류 지점까지만 안내받고, 모두 모이면 목적지가 공개된다.
            runNavigation { replace -> navigation.applyDestination(meeting, replace) }
        } else {
            val finalDestination = NavigationTarget(destination.name, destination.lat, destination.lon)
            runNavigation { replace -> navigation.applyMeetingPoint(meeting, finalDestination, replace) }
        }
    }

    private fun navigationTarget(point: MeetingCandidate): NavigationTarget? {
        val lat = point.latitude ?: return null
        val lon = point.longitude ?: return null
        return NavigationTarget(point.name, lat, lon, placeId = point.placeId)
    }

    /** 합류 후 목적지까지 그룹 이동을 이어 간다. 경유지가 끝났으므로 목적지만 다시 설정한다. */
    fun continueToDestination() {
        val destination = _state.value.group?.destination ?: return
        val target = NavigationTarget(destination.name, destination.lat, destination.lon)
        runNavigation { replace -> container.navigation.applyDestination(target, replace) }
    }

    /** 운전자가 내비의 목적지를 그룹 목적지로 바꾸겠다고 확인했다. */
    fun confirmDestinationChange() {
        val request = pendingNavigation ?: return
        pendingNavigation = null
        runNavigation(replaceDestination = true, request = request)
    }

    fun cancelDestinationChange() {
        pendingNavigation = null
        _state.update { it.copy(navigation = NavigationUiState.Idle) }
    }

    private fun runNavigation(replaceDestination: Boolean = false, request: suspend (Boolean) -> NavigationResult) {
        viewModelScope.launch {
            _state.update { it.copy(navigation = NavigationUiState.Requesting) }
            val result = request(replaceDestination)
            if (result is NavigationResult.DestinationMismatch) pendingNavigation = request
            publishNavigationStatus()
            _state.update {
                // 다시 등록을 마쳤으면 다시 등록하라는 안내를 남기지 않는다.
                val registered = result == NavigationResult.Applied &&
                    (it.message == MEETING_POINT_CHANGED || it.message == NAVIGATION_ROUTE_ENDED)
                it.copy(navigation = result.toUiState(), message = if (registered) null else it.message)
            }
        }
    }

    fun openNavigationScreen() {
        if (!container.navigation.openNavigationScreen()) {
            reportMessage("내비게이션 화면을 열 수 없습니다.")
        }
    }

    fun rerouteNavigation() {
        viewModelScope.launch {
            val before = _state.value.navigation
            _state.update { it.copy(navigation = NavigationUiState.Requesting) }
            val result = container.navigation.reroute()
            // 재탐색은 등록 상태를 바꾸지 않는다. 실패해도 등록된 경유지는 그대로다.
            val message = when (result) {
                NavigationResult.Applied -> null
                // 내비가 재탐색에 응답하지 않아 결과는 내비 화면에서만 알 수 있다.
                NavigationResult.Unconfirmed -> "내비에 경로 재탐색을 요청했습니다."
                else -> (result.toUiState() as? NavigationUiState.Failed)?.message
            }
            _state.update { it.copy(navigation = before, message = message) }
        }
    }

    private fun NavigationResult.toUiState(): NavigationUiState = when (this) {
        NavigationResult.Applied -> NavigationUiState.Applied
        NavigationResult.RecordedOnly -> NavigationUiState.RecordedOnly
        NavigationResult.Unconfirmed -> NavigationUiState.Unconfirmed
        is NavigationResult.DestinationMismatch ->
            NavigationUiState.ConfirmDestination(current.name, requested.name)
        is NavigationResult.Failed -> NavigationUiState.Failed(reason)
        NavigationResult.TimedOut -> NavigationUiState.Failed("내비게이션 응답이 없습니다. 내비 화면에서 확인해 주세요.")
        NavigationResult.Unavailable -> NavigationUiState.Failed("이 차량에서는 내비게이션 연동을 쓸 수 없습니다.")
    }

    fun setVoiceEnabled(enabled: Boolean) = _state.update {
        it.copy(voiceEnabled = enabled, voiceAvailable = container.voice.isAvailable)
    }

    fun leaveGroup() = perform {
        stopSharing()
        requireRepository().leave()
        finishLocally(Screen.HOME)
    }

    fun completeSession() = perform {
        stopSharing()
        requireRepository().complete()
        finishLocally(Screen.COMPLETED)
    }

    fun backToHome() = finishLocally(Screen.HOME)

    private fun finishLocally(screen: Screen) {
        cancelDestinationSearch()
        container.sessionStore.clear()
        withdrawalPending = false
        sessionJobs.forEach(Job::cancel)
        container.repository?.close()
        container.repository = null
        _state.update {
            MeetRouteUiState(
                screen = screen,
                group = if (screen == Screen.COMPLETED) it.group?.copy(phase = GroupPhase.CLOSED) else null,
                serverUrl = it.serverUrl,
                navigationLabel = it.navigationLabel,
                navigationConnected = it.navigationConnected,
                isDriving = it.isDriving,
                nowMillis = it.nowMillis,
            )
        }
        forgetNavigationPlan()
    }

    private fun requireRepository(): MeetPointRepository =
        repository ?: throw MeetRouteException("NO_SESSION", "참여 중인 그룹이 없습니다.")

    private fun perform(action: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: MeetRouteException) {
                _state.update { it.copy(message = error.message) }
            } catch (error: SerializationException) {
                // 서버 주소가 MeetRoute 서버가 아니거나 버전이 맞지 않는 경우
                _state.update { it.copy(message = "서버 응답을 읽지 못했습니다. 서버 주소를 확인해 주세요.") }
            } catch (error: Exception) {
                // 화면 조작 하나의 실패로 앱이 종료되면 위치 공유까지 함께 끊긴다.
                Log.e(TAG, "처리하지 못한 오류", error)
                _state.update { it.copy(message = "요청을 처리하지 못했습니다. 다시 시도해 주세요.") }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    // 음성 엔진은 앱 전역 객체라 여기서 닫으면 다음 화면에서 쓸 수 없다.
    override fun onCleared() {
        sessionJobs.forEach(Job::cancel)
    }
}

/**
 * 목적지, 만남 목적, 식사 시각이 바뀌면 이전 추천은 더 이상 유효하지 않다.
 * 비공개 드라이브의 그룹원은 목적지가 계속 비어 있으므로 코스 식별값으로 바뀐 것을 안다.
 */
fun isPlanChanged(before: GroupState, after: GroupState): Boolean =
    before.destination != after.destination ||
        before.freeDrive?.planId != after.freeDrive?.planId ||
        before.meetingPurpose != after.meetingPurpose ||
        before.mealTime != after.mealTime

/** 대기실에서는 추천 결과를 바로 보여 주고, 주행 중에는 화면을 함부로 바꾸지 않는다. */
fun screenOnRecommendations(current: Screen): Screen =
    if (current == Screen.LOBBY) Screen.RECOMMENDATIONS else current

/** 다른 차량의 결정으로 그룹이 바뀌었을 때 이동할 화면. */
fun screenAfterUpdate(before: GroupState, after: GroupState, current: Screen): Screen = when {
    isPlanChanged(before, after) -> screenFor(after, Screen.LOBBY)
    before.selectedMeetingPointId != after.selectedMeetingPointId -> screenFor(after, Screen.DRIVING)
    else -> screenFor(after, current)
}

/** 그룹 단계가 바뀌면 그에 맞는 화면으로 이동한다. */
fun screenFor(group: GroupState, current: Screen): Screen = when (group.phase) {
    GroupPhase.CLOSED, GroupPhase.ALL_ARRIVED -> Screen.COMPLETED
    GroupPhase.EN_ROUTE -> if (current == Screen.RECOMMENDATIONS) current else Screen.DRIVING
    GroupPhase.FORMING -> if (current == Screen.RECOMMENDATIONS) current else Screen.LOBBY
}
