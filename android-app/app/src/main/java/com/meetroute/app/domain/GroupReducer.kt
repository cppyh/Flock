package com.meetroute.app.domain

import java.time.Instant

const val STALE_AFTER_MILLIS = 20_000L

/** 실시간 이벤트를 화면 상태에 반영한다. */
object GroupReducer {

    fun reduce(group: GroupState, event: SessionEvent): GroupState = when (event) {
        is SessionEvent.ParticipantUpdated -> mergeParticipant(group, event.participant)
        is SessionEvent.GroupUpdated -> mergeGroup(group, event.group)
        is SessionEvent.SessionClosed -> group.copy(phase = GroupPhase.CLOSED)
        is SessionEvent.RecommendationsReady,
        is SessionEvent.RecommendationsSynced,
        is SessionEvent.ServerError,
        -> group
    }

    private fun mergeGroup(current: GroupState, incoming: GroupState): GroupState {
        if (incoming.version < current.version) return current
        if (incoming.version > current.version) return incoming
        // 같은 판이면 조회 응답보다 먼저 도착한 실시간 위치가 더 새로울 수 있다.
        val newest = incoming.participants.map { fresh ->
            val known = current.participants.find { it.vehicleId == fresh.vehicleId }
            if (known != null && isOlder(fresh, known)) known else fresh
        }
        return incoming.withParticipants(newest)
    }

    private fun mergeParticipant(group: GroupState, incoming: Participant): GroupState {
        if (incoming.status == ParticipantStatus.LEFT) {
            return group.withParticipants(group.participants.filterNot { it.vehicleId == incoming.vehicleId })
        }
        val index = group.participants.indexOfFirst { it.vehicleId == incoming.vehicleId }
        if (index < 0) return group.withParticipants(group.participants + incoming)

        val current = group.participants[index]
        val merged = if (isOlder(incoming, current)) {
            // 늦게 도착한 위치로 최신 좌표를 덮지 않되 연결 상태는 반영한다.
            current.copy(online = incoming.online)
        } else {
            incoming
        }
        return group.withParticipants(group.participants.toMutableList().also { it[index] = merged })
    }

    /** 서버는 지연을 차량 이벤트로만 알린다. 화면과 음성은 그룹 값을 보므로 여기서 함께 맞춘다. */
    private fun GroupState.withParticipants(updated: List<Participant>) =
        copy(participants = updated, rerouteSuggested = updated.any { it.delayed })

    private fun isOlder(incoming: Participant, current: Participant): Boolean {
        val incomingTime = incoming.timestamp?.let(Instant::parse) ?: return false
        val currentTime = current.timestamp?.let(Instant::parse) ?: return false
        return incomingTime.isBefore(currentTime)
    }
}

/** 서버 이벤트가 끊겨도 오래된 위치를 최신처럼 보이지 않도록 화면에서 다시 판단한다. */
fun Participant.effectiveStatus(nowMillis: Long): ParticipantStatus {
    if (status != ParticipantStatus.ACTIVE) return status
    val measuredAt = timestamp?.let(Instant::parse)?.toEpochMilli() ?: return status
    return if (nowMillis - measuredAt >= STALE_AFTER_MILLIS) ParticipantStatus.STALE else status
}
