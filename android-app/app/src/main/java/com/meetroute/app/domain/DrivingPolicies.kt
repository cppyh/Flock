package com.meetroute.app.domain

import com.meetroute.app.location.LocationFix
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_METERS = 6_371_000.0

fun distanceMeters(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
    val lat1 = Math.toRadians(fromLat)
    val lat2 = Math.toRadians(toLat)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(toLon - fromLon)
    val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(h))
}

// 정차한 차량이 연결 지연으로 보이지 않도록 STALE_AFTER_MILLIS보다 짧아야 한다.
const val STOPPED_INTERVAL_MILLIS = 15_000L

/** 이동 중에는 짧게, 정차 중에는 길게 전송 간격을 둔다(기획서 F02). */
class LocationPublishPolicy(
    private val movingIntervalMillis: Long = 5_000,
    private val stoppedIntervalMillis: Long = STOPPED_INTERVAL_MILLIS,
    private val minMovementMeters: Double = 15.0,
) {
    private var lastSent: LocationFix? = null

    fun shouldSend(fix: LocationFix, force: Boolean = false): Boolean {
        val previous = lastSent
        val send = force || previous == null || isDue(previous, fix)
        if (send) lastSent = fix
        return send
    }

    private fun isDue(previous: LocationFix, fix: LocationFix): Boolean {
        val elapsed = fix.timestampMillis - previous.timestampMillis
        val moved = distanceMeters(previous.lat, previous.lon, fix.lat, fix.lon)
        val interval = if (moved >= minMovementMeters) movingIntervalMillis else stoppedIntervalMillis
        return elapsed >= interval
    }

    fun reset() {
        lastSent = null
    }
}

/**
 * 연속된 위치로 주행 여부를 판단한다.
 *
 * 신호 대기에서 화면이 바뀌지 않도록 멈춘 뒤 일정 시간이 지나야 정차로 본다.
 */
class DrivingStateDetector(
    private val movingSpeedMps: Double = 1.5,
    private val stopHoldMillis: Long = 10_000,
) {
    private var previous: LocationFix? = null
    private var lastMovingAt: Long? = null

    fun update(fix: LocationFix): Boolean {
        val last = previous
        previous = fix
        if (last != null) {
            val seconds = (fix.timestampMillis - last.timestampMillis) / 1000.0
            if (seconds > 0) {
                val speed = distanceMeters(last.lat, last.lon, fix.lat, fix.lon) / seconds
                if (speed >= movingSpeedMps) lastMovingAt = fix.timestampMillis
            }
        }
        val movedAt = lastMovingAt ?: return false
        return fix.timestampMillis - movedAt < stopHoldMillis
    }
}

/** 상태 변화를 음성 안내 문구로 바꾼다(기획서 F07). */
class GroupAnnouncer(private val etaThresholdSeconds: Double = 600.0) {

    fun announcements(previous: GroupState?, current: GroupState, myVehicleId: String?): List<String> {
        if (previous == null) return emptyList()
        val messages = mutableListOf<String>()
        val others = current.participants.filter { it.vehicleId != myVehicleId }
        for (vehicle in others) {
            val before = previous.participants.find { it.vehicleId == vehicle.vehicleId } ?: continue
            if (vehicle.arrived && !before.arrived) {
                messages += "${vehicle.nickname} 차량이 합류 장소에 도착했습니다."
            } else if (crossedEtaThreshold(before, vehicle)) {
                messages += "${vehicle.nickname} 차량이 합류 지점까지 약 10분 남았습니다."
            }
        }
        chargingReminder(previous, current, myVehicleId)?.let(messages::add)
        if (current.phase == GroupPhase.ALL_ARRIVED && previous.phase != GroupPhase.ALL_ARRIVED) {
            messages += "모든 차량이 합류 장소에 도착했습니다."
        }
        // 비공개 드라이브는 모두 모인 순간 모든 차량에서 함께 목적지를 알린다.
        val destination = current.destination
        if (destination != null && current.freeDrive?.revealed == true && previous.freeDrive?.revealed == false) {
            messages += "오늘의 목적지는 ${destination.name}입니다."
        }
        val point = current.meetingPoint
        if (point != null && current.selectedMeetingPointId != previous.selectedMeetingPointId) {
            val verb = if (previous.selectedMeetingPointId == null) "정해졌습니다" else "변경되었습니다"
            messages += "합류 장소가 $verb. ${point.name}입니다."
        }
        if (current.rerouteSuggested && !previous.rerouteSuggested) {
            messages += "새로운 합류 장소를 추천할 수 있습니다."
        }
        return messages
    }

    /** 내 전기차가 충전기 있는 합류 장소에 막 도착했고 충전이 필요하면, 기다리는 동안 충전하라고 알린다. */
    private fun chargingReminder(previous: GroupState, current: GroupState, myVehicleId: String?): String? {
        val me = current.participants.find { it.vehicleId == myVehicleId } ?: return null
        val before = previous.participants.find { it.vehicleId == myVehicleId } ?: return null
        if (!me.arrived || before.arrived) return null
        val plan = current.meetingPoint?.evPlan?.get(me.vehicleId) ?: return null
        if (!plan.chargerAvailable || plan.chargeMinutes < 1) return null
        return if (plan.needsCharge) {
            "기다리는 동안 충전하세요. 약 ${formatMinutes(plan.chargeMinutes)} 필요합니다."
        } else if (plan.bonusPercent >= 5) {
            "충전기가 있습니다. 기다리는 동안 충전할 수 있습니다."
        } else {
            null
        }
    }

    private fun crossedEtaThreshold(before: Participant, now: Participant): Boolean {
        val previousEta = before.etaSeconds ?: return false
        val currentEta = now.etaSeconds ?: return false
        return previousEta > etaThresholdSeconds && currentEta <= etaThresholdSeconds
    }
}
