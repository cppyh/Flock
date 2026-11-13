package com.meetroute.app.location

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "MeetRouteDriving"

/** 차량이 알려 주는 주행 제한 상태. 위치 공유와 상관없이 항상 쓸 수 있다. null이면 알 수 없다. */
interface DrivingRestrictionSource {
    val restricted: StateFlow<Boolean?>
    fun start()
}

/**
 * AAOS의 UX 제한 상태. 일반 Android에서는 null로 남고, 화면은 위치로 추정한 주행 여부를 쓴다.
 */
class CarUxRestrictionSource(private val context: Context) : DrivingRestrictionSource {

    private val _restricted = MutableStateFlow<Boolean?>(null)
    override val restricted: StateFlow<Boolean?> = _restricted.asStateFlow()

    private var car: Car? = null

    override fun start() {
        if (car != null) return
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
        // android.car 라이브러리가 없는 이미지에서도 앱이 멈추면 안 된다.
        runCatching {
            val connected = Car.createCar(context) ?: return
            val manager = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            _restricted.value = manager.currentCarUxRestrictions.isRequiresDistractionOptimization
            manager.registerListener { restrictions ->
                Log.i(TAG, "주행 제한 ${restrictions.isRequiresDistractionOptimization}")
                _restricted.value = restrictions.isRequiresDistractionOptimization
            }
            car = connected
            Log.i(TAG, "주행 제한 초기값 ${_restricted.value}")
        }.onFailure { error -> Log.w(TAG, "주행 제한 상태를 읽지 못했습니다: ${error.javaClass.simpleName}") }
    }
}
