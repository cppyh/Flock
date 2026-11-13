package com.meetroute.app.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
private val KST = ZoneId.of("Asia/Seoul")

fun formatMinutes(minutes: Double): String {
    val rounded = minutes.roundToInt()
    val hours = rounded / 60
    val rest = rounded % 60
    return when {
        hours > 0 && rest > 0 -> "${hours}시간 ${rest}분"
        hours > 0 -> "${hours}시간"
        else -> "${rest}분"
    }
}

fun formatEta(seconds: Double?): String =
    if (seconds == null) "계산 전" else formatMinutes(seconds / 60)

fun formatClock(isoTime: String, zone: ZoneId = KST): String =
    CLOCK_FORMAT.format(Instant.parse(isoTime).atZone(zone))

fun formatElapsed(isoTime: String?, nowMillis: Long): String {
    if (isoTime == null) return "기록 없음"
    val seconds = (nowMillis - Instant.parse(isoTime).toEpochMilli()) / 1000
    return when {
        seconds < 5 -> "방금"
        seconds < 60 -> "${seconds}초 전"
        else -> "${seconds / 60}분 전"
    }
}

fun routeSourceLabel(source: String): String = when (source) {
    "OSRM" -> "실제 도로망 기준 · 실시간 교통 미반영"
    "ESTIMATE" -> "모의 데이터 · 직선거리 추정치"
    else -> source
}

fun etaSourceLabel(source: String?): String = when (source) {
    "VEHICLE_NAV" -> "차량 내비"
    "ROAD_ROUTE" -> "도로 경로"
    else -> ""
}

/** "바다 · 왕복 약 2시간 5분" */
fun freeDriveSummary(drive: FreeDrive): String = buildString {
    append(drive.theme.label)
    drive.roundTripMinutes?.let { append(" · 왕복 약 ${formatMinutes(it)}") }
}

fun batteryLabel(percent: Double): String = "배터리 ${percent.roundToInt()}%"

/** 추천 카드의 전기차 한 줄. 운전 중에는 보이지 않는 상세 정보다. */
fun evPlanLine(nickname: String, plan: EvPlan): String = buildString {
    append("$nickname: 도착 시 ${plan.batteryAtArrival.coerceAtLeast(0.0).roundToInt()}%")
    when {
        plan.needsCharge && !plan.chargerAvailable -> append(" · 충전소 없음, 따로 충전 ${formatMinutes(plan.extraWaitMinutes)}")
        plan.needsCharge && plan.extraWaitMinutes < 1 -> append(" · 충전 ${formatMinutes(plan.chargeMinutes)}, 대기 중 끝남")
        plan.needsCharge -> append(
            " · 충전 ${formatMinutes(plan.chargeMinutes)} 중 ${formatMinutes(plan.coveredByWaitMinutes)} 대기와 겹침"
        )
        plan.bonusPercent >= 5 -> append(" · 대기 중 +${plan.bonusPercent.roundToInt()}% 충전 가능")
        else -> append(" · 충전 필요 없음")
    }
}
