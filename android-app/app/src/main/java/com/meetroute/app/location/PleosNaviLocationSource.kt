package com.meetroute.app.location

import com.meetroute.app.navigation.NavigationGateway
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val POLL_INTERVAL_MILLIS = 5_000L

/** FusedLocation을 쓸 수 없을 때의 2순위 공급자. NaviHelper의 현재 위치를 주기적으로 요청한다. */
class PleosNaviLocationSource(
    private val navigation: NavigationGateway,
    private val clock: () -> Long = System::currentTimeMillis,
) : VehicleLocationSource {

    override val type = LocationSourceType.PLEOS_NAVI

    override fun isAvailable(): Boolean = navigation.supportsVehicleLocation

    override fun updates(): Flow<LocationFix> = flow {
        while (true) {
            navigation.currentLocation()?.let { position ->
                // CurrentLocationInfo에는 측정 시각이 없어 수신 시각을 쓴다.
                emit(
                    LocationFix(
                        lat = position.lat,
                        lon = position.lon,
                        timestampMillis = clock(),
                        heading = position.heading,
                        source = type,
                    )
                )
            }
            delay(POLL_INTERVAL_MILLIS)
        }
    }
}
