package com.meetroute.app.location

import com.meetroute.app.domain.DrivingStateDetector
import com.meetroute.app.domain.LocationPublishPolicy
import com.meetroute.app.domain.MeetPointRepository
import com.meetroute.app.navigation.NavigationGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

private const val SILENCE_LIMIT_MILLIS = 30_000L
private const val RETRY_DELAY_MILLIS = 60_000L

/**
 * 위치를 읽어 정책에 맞게 서버로 보낸다.
 *
 * 공급자가 실패하거나 일정 시간 쓸 수 있는 값을 주지 않으면 다음 순위로 넘어간다. 모두 실패해도 멈추지 않고
 * 잠시 뒤 처음부터 다시 시도한다. 서비스가 늦게 뜨거나 위치가 나중에 들어오는 환경이 있기 때문이다.
 */
@OptIn(FlowPreview::class)
class LocationSharingController(
    private val selector: LocationSourceSelector,
    private val navigation: NavigationGateway,
    private val scope: CoroutineScope,
    private val policy: LocationPublishPolicy = LocationPublishPolicy(),
    private val detector: DrivingStateDetector = DrivingStateDetector(),
    private val silenceLimitMillis: Long = SILENCE_LIMIT_MILLIS,
    private val retryDelayMillis: Long = RETRY_DELAY_MILLIS,
    private val battery: BatterySource = NoBatterySource,
) {
    private val _activeSource = MutableStateFlow<LocationSourceType?>(null)
    val activeSource: StateFlow<LocationSourceType?> = _activeSource.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    private val _isDriving = MutableStateFlow(false)
    val isDriving: StateFlow<Boolean> = _isDriving.asStateFlow()

    private var job: Job? = null

    // 도착은 한 번만 바로 알린다. 그 뒤에는 정차 전송 간격을 따른다.
    private var reportedArrival: String? = null

    fun start(repository: MeetPointRepository) {
        job?.cancel()
        policy.reset()
        reportedArrival = null
        _problem.value = null
        battery.start()
        job = scope.launch {
            navigation.ensureConnected()
            while (isActive) {
                for (source in selector.availableInOrder()) {
                    if (collect(source, repository)) return@launch
                    _isDriving.value = false
                }
                _activeSource.value = null
                _problem.value = "차량 위치를 가져올 수 없습니다. 권한과 지원 환경을 확인해 주세요."
                delay(retryDelayMillis)
            }
        }
    }

    /** 공급자가 스스로 끝나면 true, 실패하거나 응답이 없어 다음 공급자로 넘어가야 하면 false다. */
    private suspend fun collect(source: VehicleLocationSource, repository: MeetPointRepository): Boolean {
        _activeSource.value = source.type
        return try {
            source.updates()
                .filter { fix -> fix.isInServiceArea() }
                .timeout(silenceLimitMillis.milliseconds)
                .collect { fix -> handle(fix, repository) }
            true
        } catch (error: TimeoutCancellationException) {
            _problem.value = "${source.type.label}에서 쓸 수 있는 위치가 오지 않아 다음 위치 공급자로 전환합니다."
            false
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _problem.value = "${source.type.label}를 쓸 수 없어 다음 위치 공급자로 전환합니다."
            false
        }
    }

    private suspend fun handle(fix: LocationFix, repository: MeetPointRepository) {
        _isDriving.value = detector.update(fix)
        val progress = navigation.progress.value
        val arrival = progress?.takeIf { it.arrived }?.let { it.targetId.orEmpty() }
        val arrivalPending = arrival != null && arrival != reportedArrival
        if (!policy.shouldSend(fix, force = arrivalPending)) return
        navigation.refreshProgress()
        try {
            repository.publishLocation(fix.copy(batteryPercent = battery.percent.value), progress)
            _problem.value = null
            if (arrivalPending) reportedArrival = arrival
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // 전송 실패로 공유를 멈추지 않는다. 다음 위치로 다시 시도한다.
            _problem.value = error.message ?: "위치를 전송하지 못했습니다."
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        battery.stop()
        _activeSource.value = null
        _isDriving.value = false
        _problem.value = null
    }
}
