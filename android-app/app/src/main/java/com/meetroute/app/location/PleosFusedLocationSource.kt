package com.meetroute.app.location

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

const val FUSED_LOCATION_PERMISSION = "pleos.car.permission.FUSED_LOCATION"

private const val TAG = "MeetRouteLocation"

/**
 * 1순위 위치 공급자. release() 후에는 재사용할 수 없어 수집할 때마다 새로 만든다.
 *
 * 콜백은 위치가 바뀔 때만 오므로 세워 둔 차량은 위치를 받지 못할 수 있다. 시작할 때와 콜백이 뜸할 때
 * 마지막 위치를 직접 조회한다. 차량의 마지막 위치는 움직이지 않았다는 뜻이므로 수신 시각을 쓴다.
 */
class PleosFusedLocationSource(
    private val context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) : VehicleLocationSource {

    override val type = LocationSourceType.PLEOS_FUSED

    override fun isAvailable(): Boolean {
        val granted = context.checkSelfPermission(FUSED_LOCATION_PERMISSION) == PackageManager.PERMISSION_GRANTED
        return granted && runCatching { FusedLocation(context).isSupported() }.getOrDefault(false)
    }

    override fun updates(): Flow<LocationFix> = callbackFlow {
        val sdk = FusedLocation(context)
        var lastChangedAt = clock()
        val listener = object : FusedLocationListener {
            override fun onChanged(location: LocationData?) {
                location ?: return
                val now = clock()
                lastChangedAt = now
                trySend(location.toFix(plausibleTimestamp(location.timestampMillis, now)))
            }

            override fun onError(exception: Exception) {
                Log.w(TAG, "FusedLocation 오류: ${exception.message}")
                close(exception)
            }
        }
        // 조회 실패는 주기 확인 하나가 빠진 것이다. 값이 계속 없으면 수집 쪽이 다음 공급자로 넘어간다.
        val lastKnown = object : FusedLocationListener {
            override fun onChanged(location: LocationData?) {
                location?.let { trySend(it.toFix(clock())) }
            }

            override fun onError(exception: Exception) {
                Log.w(TAG, "마지막 위치 조회 실패: ${exception.message}")
            }
        }
        sdk.initialize()
        sdk.registerFusedLocationCallback(listener)
        sdk.getLastKnownLocation(lastKnown)
        launch {
            while (isActive) {
                delay(STATIONARY_POLL_MILLIS)
                if (clock() - lastChangedAt >= STATIONARY_POLL_MILLIS) sdk.getLastKnownLocation(lastKnown)
            }
        }
        awaitClose {
            runCatching {
                sdk.unregisterFusedLocationCallback(listener)
                sdk.release()
            }
        }
    }

    private fun LocationData.toFix(timestamp: Long) = LocationFix(
        lat = latitude,
        lon = longitude,
        timestampMillis = timestamp,
        heading = heading.toDouble(),
        source = type,
    )
}
