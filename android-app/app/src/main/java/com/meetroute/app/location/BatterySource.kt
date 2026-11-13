package com.meetroute.app.location

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.EvBattery
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

const val CAR_ENERGY_PERMISSION = "pleos.car.permission.CAR_ENERGY"

private const val TAG = "MeetRouteBattery"

// 배터리 잔량은 천천히 바뀐다. 변경 알림이 오지 않는 환경을 위해 이 주기로 다시 읽는다.
private const val BATTERY_POLL_MILLIS = 30_000L

/** 전기차 배터리 잔량. 위치와 함께 서버로 보내 합류 지점에서 기다리는 시간을 충전 시간으로 쓰게 한다. */
interface BatterySource {
    /** 마지막으로 읽은 잔량(%). 전기차가 아니거나 권한이 없거나 읽지 못하면 null이다. */
    val percent: StateFlow<Double?>
    fun start()
    fun stop()
}

/** 배터리를 읽지 않는다. 시험과 차량 SDK가 없는 환경에서 쓴다. */
object NoBatterySource : BatterySource {
    override val percent: StateFlow<Double?> = MutableStateFlow(null)
    override fun start() = Unit
    override fun stop() = Unit
}

/** 0~100 밖의 값은 읽지 못한 것으로 본다. */
fun plausibleBatteryPercent(value: Number?): Double? =
    value?.toDouble()?.takeIf { it.isFinite() && it in 0.0..100.0 }

/**
 * Pleos Vehicle SDK의 EvBattery로 충전 상태(SoC)를 읽는다.
 *
 * 변경 알림을 등록하고, 알림이 오지 않는 환경을 위해 주기적으로 한 번씩 직접 조회한다.
 * 권한이 없거나 차량 서비스가 없으면 조용히 null로 남는다. 배터리는 추천을 돕는 값일 뿐이라
 * 실패해도 위치 공유를 막지 않는다.
 */
class PleosEvBatterySource(
    private val context: Context,
    private val scope: CoroutineScope,
) : BatterySource {

    private val _percent = MutableStateFlow<Double?>(null)
    override val percent: StateFlow<Double?> = _percent.asStateFlow()

    private var vehicle: Vehicle? = null
    private var listener: EvBatteryStateOfChargeListener? = null
    private var poll: Job? = null

    @Synchronized
    override fun start() {
        if (vehicle != null) return
        if (context.checkSelfPermission(CAR_ENERGY_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "배터리 권한이 없어 잔량을 공유하지 않습니다.")
            return
        }
        try {
            val sdk = Vehicle(context)
            sdk.initialize()
            val battery = sdk.getEvBattery()
            val callback = object : EvBatteryStateOfChargeListener {
                override fun onEvBatteryStateOfChargeUpdated(value: Int?) = publish(value)

                override fun onFailed(exception: Exception) {
                    Log.w(TAG, "배터리 알림 오류: ${exception.message}")
                }
            }
            battery.registerEvBatteryStateOfCharge(callback)
            vehicle = sdk
            listener = callback
            poll = scope.launch {
                while (isActive) {
                    read(battery)
                    delay(BATTERY_POLL_MILLIS)
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "차량 배터리를 읽을 수 없습니다: ${error.message}")
            stop()
        }
    }

    private fun read(battery: EvBattery) {
        runCatching {
            battery.getEvBatteryStateOfCharge(
                { value -> publish(value) },
                { error -> Log.w(TAG, "배터리 조회 실패: ${error.message}") },
            )
        }.onFailure { Log.w(TAG, "배터리 조회 실패: ${it.message}") }
    }

    private fun publish(value: Int?) {
        plausibleBatteryPercent(value)?.let { _percent.value = it }
    }

    @Synchronized
    override fun stop() {
        poll?.cancel()
        poll = null
        val sdk = vehicle
        val callback = listener
        vehicle = null
        listener = null
        runCatching {
            if (sdk != null && callback != null) sdk.getEvBattery().unregisterEvBatteryStateOfCharge(callback)
            sdk?.release()
        }
        // 공유를 끄면 마지막 값도 버린다. 다시 켜면 새로 읽는다.
        _percent.value = null
    }
}
