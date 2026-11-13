package com.meetroute.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val MIN_INTERVAL_MILLIS = 5_000L

/** 일반 Android 환경 검증용. FusedLocation SDK 연동 성공으로 간주하지 않는다(design.md §4). */
class AndroidLocationSource(
    private val context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) : VehicleLocationSource {

    override val type = LocationSourceType.ANDROID

    private val manager: LocationManager?
        get() = context.getSystemService(LocationManager::class.java)

    override fun isAvailable(): Boolean {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        return granted && manager?.getProviders(true)?.isNotEmpty() == true
    }

    @SuppressLint("MissingPermission")
    override fun updates(): Flow<LocationFix> = callbackFlow {
        val locationManager = checkNotNull(manager)
        var lastChangedAt = clock()
        val listener = LocationListener { location ->
            lastChangedAt = clock()
            trySend(location.toFix(location.time))
        }
        val providers = locationManager.getProviders(true)
        providers.forEach { provider ->
            locationManager.getLastKnownLocation(provider)?.let { trySend(it.toFix(clock())) }
            locationManager.requestLocationUpdates(provider, MIN_INTERVAL_MILLIS, 0f, listener, Looper.getMainLooper())
        }
        // 정지해 있으면 갱신이 오지 않는 환경이 있어 마지막 위치로 살아 있음을 알린다.
        launch {
            while (isActive) {
                delay(STATIONARY_POLL_MILLIS)
                if (clock() - lastChangedAt < STATIONARY_POLL_MILLIS) continue
                providers.firstNotNullOfOrNull(locationManager::getLastKnownLocation)
                    ?.let { trySend(it.toFix(clock())) }
            }
        }
        awaitClose { locationManager.removeUpdates(listener) }
    }

    private fun Location.toFix(timestamp: Long) = LocationFix(
        lat = latitude,
        lon = longitude,
        timestampMillis = timestamp,
        heading = if (hasBearing()) bearing.toDouble() else null,
        accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null,
        source = type,
    )
}
