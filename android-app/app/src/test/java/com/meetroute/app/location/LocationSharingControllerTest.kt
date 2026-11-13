package com.meetroute.app.location

import com.meetroute.app.domain.ConnectionState
import com.meetroute.app.domain.Destination
import com.meetroute.app.domain.DriveTheme
import com.meetroute.app.domain.GroupState
import com.meetroute.app.domain.MeetPointRepository
import com.meetroute.app.domain.MeetRouteException
import com.meetroute.app.domain.MeetingPurpose
import com.meetroute.app.domain.NavProgress
import com.meetroute.app.domain.RecommendationSet
import com.meetroute.app.domain.STALE_AFTER_MILLIS
import com.meetroute.app.domain.Session
import com.meetroute.app.domain.SessionEvent
import com.meetroute.app.domain.StoredSession
import com.meetroute.app.navigation.NavigationGateway
import com.meetroute.app.navigation.RecordingNavigationGateway
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun fix(seconds: Long, source: LocationSourceType = LocationSourceType.PLEOS_FUSED) =
    LocationFix(37.5 + seconds * 0.001, 127.0, 1_800_000_000_000 + seconds * 1000, source = source)

private class FakeSource(
    override val type: LocationSourceType,
    private val available: Boolean = true,
    private val flowFactory: () -> Flow<LocationFix> = { emptyFlow() },
) : VehicleLocationSource {
    override fun isAvailable() = available
    override fun updates() = flowFactory()
}

/** 진행 상황만 정해 두는 내비 대역. */
private class ProgressGateway(progress: NavProgress?) : NavigationGateway by RecordingNavigationGateway() {
    val current = MutableStateFlow(progress)
    override val progress: StateFlow<NavProgress?> = current
}

private class FakeRepository(
    private val failing: Boolean = false,
    private val failure: Exception? = null,
) : MeetPointRepository {
    val published = mutableListOf<LocationFix>()
    override val events: Flow<SessionEvent> = emptyFlow()
    override val connection: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.CONNECTED)
    override val dataLabel: String? = null

    override suspend fun publishLocation(fix: LocationFix, progress: NavProgress?) {
        if (failing) throw MeetRouteException("NETWORK", "서버에 연결할 수 없습니다.")
        failure?.let { throw it }
        published += fix
    }

    override suspend fun createGroup(name: String, nickname: String): Session = error("not used")
    override suspend fun joinGroup(inviteCode: String, nickname: String): Session = error("not used")
    override suspend fun resume(session: StoredSession): GroupState = error("not used")
    override suspend fun refresh(): GroupState = error("not used")
    override suspend fun setDestination(destination: Destination): GroupState = error("not used")
    override suspend fun setPurpose(purpose: MeetingPurpose, mealTime: String?): GroupState = error("not used")
    override suspend fun startFreeDrive(hours: Int, theme: DriveTheme, privateDestination: Boolean, reroll: Boolean): GroupState =
        error("not used")
    override suspend fun setLocationSharing(enabled: Boolean) = Unit
    override suspend fun requestRecommendations(): RecommendationSet = error("not used")
    override suspend fun selectMeetingPoint(recommendationId: String, placeId: String, expectedVersion: Int): GroupState =
        error("not used")
    override suspend fun leave() = Unit
    override suspend fun complete() = Unit
    override fun close() = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class LocationSharingControllerTest {

    @Test
    fun `sources are tried in documented priority`() {
        val fused = FakeSource(LocationSourceType.PLEOS_FUSED, available = false)
        val navi = FakeSource(LocationSourceType.PLEOS_NAVI)
        val android = FakeSource(LocationSourceType.ANDROID)
        val selector = LocationSourceSelector(listOf(fused, navi, android))
        assertEquals(listOf(navi, android), selector.availableInOrder())
    }

    @Test
    fun `fixes are published and active source is shown`() = runTest {
        val repository = FakeRepository()
        val source = FakeSource(LocationSourceType.PLEOS_FUSED) {
            flow {
                emit(fix(0))
                awaitCancellation()
            }
        }
        val controller = LocationSharingController(
            LocationSourceSelector(listOf(source)), RecordingNavigationGateway(), backgroundScope,
        )
        controller.start(repository)
        runCurrent()
        assertEquals(1, repository.published.size)
        assertEquals(LocationSourceType.PLEOS_FUSED, controller.activeSource.value)
        assertNull(controller.problem.value)
    }

    @Test
    fun `failing source falls back to next source`() = runTest {
        val repository = FakeRepository()
        val fused = FakeSource(LocationSourceType.PLEOS_FUSED) { flow { throw IllegalStateException("SDK 오류") } }
        val navi = FakeSource(LocationSourceType.PLEOS_NAVI) {
            flow {
                emit(fix(0, LocationSourceType.PLEOS_NAVI))
                awaitCancellation()
            }
        }
        val controller = LocationSharingController(
            LocationSourceSelector(listOf(fused, navi)), RecordingNavigationGateway(), backgroundScope,
        )
        controller.start(repository)
        runCurrent()
        assertEquals(LocationSourceType.PLEOS_NAVI, controller.activeSource.value)
        assertEquals(LocationSourceType.PLEOS_NAVI, repository.published.single().source)
    }

    @Test
    fun `no usable source reports a problem instead of crashing`() = runTest {
        val controller = LocationSharingController(
            LocationSourceSelector(emptyList()), RecordingNavigationGateway(), backgroundScope,
        )
        controller.start(FakeRepository())
        runCurrent()
        assertNull(controller.activeSource.value)
        assertNotNull(controller.problem.value)
    }

    @Test
    fun `send failure keeps sharing alive and shows reason`() = runTest {
        val source = FakeSource(LocationSourceType.PLEOS_FUSED) {
            flow {
                emit(fix(0))
                awaitCancellation()
            }
        }
        val controller = LocationSharingController(
            LocationSourceSelector(listOf(source)), RecordingNavigationGateway(), backgroundScope,
        )
        controller.start(FakeRepository(failing = true))
        runCurrent()
        assertEquals(LocationSourceType.PLEOS_FUSED, controller.activeSource.value)
        assertEquals("서버에 연결할 수 없습니다.", controller.problem.value)
    }

    @Test
    fun `stop ends collection so nothing more is sent`() = runTest {
        val repository = FakeRepository()
        var cancelled = false
        val source = FakeSource(LocationSourceType.PLEOS_FUSED) {
            flow {
                emit(fix(0))
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        }
        val controller = LocationSharingController(
            LocationSourceSelector(listOf(source)), RecordingNavigationGateway(), backgroundScope,
        )
        controller.start(repository)
        runCurrent()
        controller.stop()
        runCurrent()
        assertTrue(cancelled)
        assertNull(controller.activeSource.value)
        assertFalse(controller.isDriving.value)
    }

    @Test
    fun `silent source falls back to the next source`() = runTest {
        val repository = FakeRepository()
        val silent = FakeSource(LocationSourceType.PLEOS_FUSED) { flow { awaitCancellation() } }
        val navi = FakeSource(LocationSourceType.PLEOS_NAVI) {
            flow {
                emit(fix(0, LocationSourceType.PLEOS_NAVI))
                awaitCancellation()
            }
        }
        val controller = LocationSharingController(
            LocationSourceSelector(listOf(silent, navi)), RecordingNavigationGateway(), backgroundScope,
        )
        controller.start(repository)
        advanceTimeBy(31_000)
        runCurrent()
        assertEquals(LocationSourceType.PLEOS_NAVI, controller.activeSource.value)
        assertEquals(1, repository.published.size)
    }

    @Test
    fun `source stuck outside the service area falls back to the next source`() = runTest {
        val repository = FakeRepository()
        val outside = FakeSource(LocationSourceType.PLEOS_FUSED) {
            flow {
                while (true) {
                    emit(LocationFix(0.0, 0.0, 1_800_000_000_000, source = LocationSourceType.PLEOS_FUSED))
                    delay(1_000)
                }
            }
        }
        val navi = FakeSource(LocationSourceType.PLEOS_NAVI) {
            flow {
                emit(fix(0, LocationSourceType.PLEOS_NAVI))
                awaitCancellation()
            }
        }
        val controller = LocationSharingController(
            LocationSourceSelector(listOf(outside, navi)), RecordingNavigationGateway(), backgroundScope,
        )
        controller.start(repository)
        advanceTimeBy(31_000)
        runCurrent()
        assertEquals(LocationSourceType.PLEOS_NAVI, controller.activeSource.value)
        assertEquals(LocationSourceType.PLEOS_NAVI, repository.published.single().source)
    }

    @Test
    fun `service area matches the server limits`() {
        fun at(lat: Double, lon: Double) = LocationFix(lat, lon, 0, source = LocationSourceType.PLEOS_NAVI)
        assertTrue(at(37.5, 127.0).isInServiceArea())
        assertFalse(at(0.0, 0.0).isInServiceArea())
        assertFalse(at(37.42, -122.08).isInServiceArea())
    }

    @Test
    fun `silent source is explained while the next source has no fix yet`() = runTest {
        val silent = FakeSource(LocationSourceType.PLEOS_FUSED) { flow { awaitCancellation() } }
        val quiet = FakeSource(LocationSourceType.PLEOS_NAVI) { flow { awaitCancellation() } }
        val controller = LocationSharingController(
            LocationSourceSelector(listOf(silent, quiet)), RecordingNavigationGateway(), backgroundScope,
        )
        controller.start(FakeRepository())
        advanceTimeBy(31_000)
        runCurrent()
        assertEquals(LocationSourceType.PLEOS_NAVI, controller.activeSource.value)
        assertNotNull(controller.problem.value)
    }

    @Test
    fun `source that later goes quiet is replaced too`() = runTest {
        val repository = FakeRepository()
        val fused = FakeSource(LocationSourceType.PLEOS_FUSED) {
            flow {
                emit(fix(0))
                awaitCancellation()
            }
        }
        val navi = FakeSource(LocationSourceType.PLEOS_NAVI) { flow { awaitCancellation() } }
        val controller = LocationSharingController(
            LocationSourceSelector(listOf(fused, navi)), RecordingNavigationGateway(), backgroundScope,
        )
        controller.start(repository)
        advanceTimeBy(31_000)
        runCurrent()
        assertEquals(LocationSourceType.PLEOS_NAVI, controller.activeSource.value)
    }

    @Test
    fun `all sources silent reports a problem and tries again later`() = runTest {
        var attempts = 0
        val silent = FakeSource(LocationSourceType.PLEOS_FUSED) {
            attempts += 1
            flow { awaitCancellation() }
        }
        val controller = LocationSharingController(
            LocationSourceSelector(listOf(silent)), RecordingNavigationGateway(), backgroundScope,
        )
        controller.start(FakeRepository())
        advanceTimeBy(31_000)
        runCurrent()
        assertNull(controller.activeSource.value)
        assertEquals("차량 위치를 가져올 수 없습니다. 권한과 지원 환경을 확인해 주세요.", controller.problem.value)

        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(2, attempts)
        assertEquals(LocationSourceType.PLEOS_FUSED, controller.activeSource.value)
    }

    @Test
    fun `stationary fixes keep the vehicle fresh and end the driving state`() = runTest {
        val repository = FakeRepository()
        val base = 1_800_000_000_000
        val source = FakeSource(LocationSourceType.PLEOS_FUSED) {
            flow {
                emit(LocationFix(37.5000, 127.0, base, source = LocationSourceType.PLEOS_FUSED))
                delay(STATIONARY_POLL_MILLIS)
                emit(LocationFix(37.5010, 127.0, base + 5_000, source = LocationSourceType.PLEOS_FUSED))
                var seconds = 5L
                while (true) {
                    delay(STATIONARY_POLL_MILLIS)
                    seconds += 5
                    emit(LocationFix(37.5010, 127.0, base + seconds * 1000, source = LocationSourceType.PLEOS_FUSED))
                }
            }
        }
        val controller = LocationSharingController(
            LocationSourceSelector(listOf(source)), RecordingNavigationGateway(), backgroundScope,
        )
        controller.start(repository)
        advanceTimeBy(5_500)
        runCurrent()
        assertTrue(controller.isDriving.value)

        advanceTimeBy(60_000)
        runCurrent()
        assertFalse(controller.isDriving.value)
        val gaps = repository.published.zipWithNext { a, b -> b.timestampMillis - a.timestampMillis }
        assertTrue("정차 전송 간격이 STALE 기준보다 짧아야 합니다: $gaps", gaps.all { it < STALE_AFTER_MILLIS })
    }

    @Test
    fun `arrival is sent at once only one time`() = runTest {
        val repository = FakeRepository()
        val navigation = ProgressGateway(NavProgress(arrived = true, targetId = "place"))
        val source = FakeSource(LocationSourceType.PLEOS_FUSED) {
            flow {
                repeat(10) { second ->
                    emit(LocationFix(37.5, 127.0, 1_800_000_000_000 + second * 1000L, source = LocationSourceType.PLEOS_FUSED))
                }
                awaitCancellation()
            }
        }
        val controller = LocationSharingController(LocationSourceSelector(listOf(source)), navigation, backgroundScope)
        controller.start(repository)
        runCurrent()
        assertEquals(1, repository.published.size)
    }

    @Test
    fun `arrival at a new target is sent at once again`() = runTest {
        val repository = FakeRepository()
        val navigation = ProgressGateway(NavProgress(arrived = true, targetId = "first"))
        val source = FakeSource(LocationSourceType.PLEOS_FUSED) {
            flow {
                emit(LocationFix(37.5, 127.0, 1_800_000_000_000, source = LocationSourceType.PLEOS_FUSED))
                delay(1_000)
                navigation.current.value = NavProgress(arrived = true, targetId = "second")
                emit(LocationFix(37.5, 127.0, 1_800_000_001_000, source = LocationSourceType.PLEOS_FUSED))
                awaitCancellation()
            }
        }
        val controller = LocationSharingController(LocationSourceSelector(listOf(source)), navigation, backgroundScope)
        controller.start(repository)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, repository.published.size)
    }

    @Test
    fun `unexpected publish failure does not change the source`() = runTest {
        val source = FakeSource(LocationSourceType.PLEOS_FUSED) {
            flow {
                emit(fix(0))
                awaitCancellation()
            }
        }
        val controller = LocationSharingController(
            LocationSourceSelector(listOf(source)), RecordingNavigationGateway(), backgroundScope,
        )
        controller.start(FakeRepository(failure = IllegalStateException("직렬화 실패")))
        runCurrent()
        assertEquals(LocationSourceType.PLEOS_FUSED, controller.activeSource.value)
        assertEquals("직렬화 실패", controller.problem.value)
    }

    private class FakeBattery(initial: Double?) : BatterySource {
        override val percent = MutableStateFlow(initial)
        var started = 0
        var stopped = 0
        override fun start() {
            started += 1
        }
        override fun stop() {
            stopped += 1
        }
    }

    @Test
    fun `battery level is attached to each published fix`() = runTest {
        val repository = FakeRepository()
        val battery = FakeBattery(42.0)
        val source = FakeSource(LocationSourceType.PLEOS_FUSED) {
            flow {
                emit(fix(0))
                delay(20_000)
                emit(fix(20))
                awaitCancellation()
            }
        }
        val controller = LocationSharingController(
            LocationSourceSelector(listOf(source)), RecordingNavigationGateway(), backgroundScope, battery = battery,
        )
        controller.start(repository)
        runCurrent()
        battery.percent.value = 41.0
        advanceTimeBy(21_000)
        runCurrent()
        assertEquals(listOf(42.0, 41.0), repository.published.map { it.batteryPercent })
        assertEquals(1, battery.started)
        controller.stop()
        assertEquals(1, battery.stopped)
    }

    @Test
    fun `vehicle without battery data sends no battery level`() = runTest {
        val repository = FakeRepository()
        val source = FakeSource(LocationSourceType.PLEOS_FUSED) {
            flow {
                emit(fix(0))
                awaitCancellation()
            }
        }
        val controller = LocationSharingController(
            LocationSourceSelector(listOf(source)), RecordingNavigationGateway(), backgroundScope, battery = FakeBattery(null),
        )
        controller.start(repository)
        runCurrent()
        assertNull(repository.published.single().batteryPercent)
    }

    @Test
    fun `implausible battery values are treated as unknown`() {
        assertEquals(55.0, plausibleBatteryPercent(55)!!, 0.0)
        assertNull(plausibleBatteryPercent(null))
        assertNull(plausibleBatteryPercent(-1))
        assertNull(plausibleBatteryPercent(101))
        assertNull(plausibleBatteryPercent(Double.NaN))
    }

    @Test
    fun `plausible measurement time is kept`() {
        assertEquals(990_000L, plausibleTimestamp(990_000, 1_000_000))
    }

    @Test
    fun `measurement time on another clock is replaced by receipt time`() {
        val now = 1_800_000_000_000
        assertEquals(now, plausibleTimestamp(123_456, now))
        assertEquals(now, plausibleTimestamp(now + 3_600_000, now))
    }

    @Test
    fun `simulated source moves to target and stops`() = runTest {
        var now = 0L
        val source = SimulatedLocationSource(37.5, 127.0, 37.5, 127.01, speedMps = 500.0, clock = { now })
        val fixes = source.updates().toList()
        assertEquals(127.0, fixes.first().lon, 0.0)
        assertEquals(127.01, fixes.last().lon, 1e-9)
        assertTrue(fixes.size > 1)
        assertEquals(LocationSourceType.SIMULATED, fixes.first().source)
        advanceTimeBy(1)
        now += 1
    }
}
