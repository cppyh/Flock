package com.meetroute.app.location

import com.meetroute.app.domain.distanceMeters
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.min

/** 시나리오 기반 테스트용. 두 지점 사이를 일정한 속도로 이동한다. */
class SimulatedLocationSource(
    private val startLat: Double,
    private val startLon: Double,
    private val targetLat: Double,
    private val targetLon: Double,
    private val speedMps: Double = 25.0,
    private val intervalMillis: Long = 5_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : VehicleLocationSource {

    override val type = LocationSourceType.SIMULATED

    override fun isAvailable(): Boolean = true

    override fun updates(): Flow<LocationFix> = flow {
        val total = distanceMeters(startLat, startLon, targetLat, targetLon)
        var travelled = 0.0
        while (true) {
            val ratio = if (total == 0.0) 1.0 else min(travelled / total, 1.0)
            emit(
                LocationFix(
                    lat = startLat + (targetLat - startLat) * ratio,
                    lon = startLon + (targetLon - startLon) * ratio,
                    timestampMillis = clock(),
                    source = type,
                )
            )
            if (ratio >= 1.0) return@flow
            delay(intervalMillis)
            travelled += speedMps * intervalMillis / 1000
        }
    }
}
