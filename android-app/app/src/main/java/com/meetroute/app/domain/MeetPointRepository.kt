package com.meetroute.app.domain

import com.meetroute.app.location.LocationFix
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** 차량 내비게이션에서 받은 합류 지점까지의 진행 상황. targetId는 어느 합류 지점의 값인지 밝힌다. */
data class NavProgress(
    val remainSeconds: Int? = null,
    val remainMeters: Int? = null,
    val arrived: Boolean = false,
    val targetId: String? = null,
)

/**
 * 그룹 세션과 합류 추천을 제공한다.
 *
 * 실제 서버와 녹화 데이터(오프라인 시연)를 같은 화면으로 쓰기 위한 경계다.
 */
interface MeetPointRepository {
    val events: Flow<SessionEvent>
    val connection: StateFlow<ConnectionState>

    /** 모의 데이터를 쓰는 구현은 화면에 표시할 문구를 돌려준다. */
    val dataLabel: String?

    suspend fun createGroup(name: String, nickname: String): Session
    suspend fun joinGroup(inviteCode: String, nickname: String): Session
    suspend fun resume(session: StoredSession): GroupState

    /** 실시간 채널을 연다. 이벤트를 놓치지 않도록 [events]를 구독하고 화면 상태를 맞춘 뒤에 호출한다. */
    fun connectRealtime() = Unit
    suspend fun refresh(): GroupState
    suspend fun setDestination(destination: Destination): GroupState
    suspend fun setPurpose(purpose: MeetingPurpose, mealTime: String?): GroupState

    /** 목적지 없는 드라이브. hours(왕복, 1~5시간)에 맞는 명소를 서버가 골라 그룹 목적지로 둔다. */
    suspend fun startFreeDrive(hours: Int, theme: DriveTheme, privateDestination: Boolean, reroll: Boolean): GroupState
    suspend fun setLocationSharing(enabled: Boolean)
    suspend fun publishLocation(fix: LocationFix, progress: NavProgress?)
    suspend fun requestRecommendations(): RecommendationSet
    suspend fun selectMeetingPoint(recommendationId: String, placeId: String, expectedVersion: Int): GroupState
    suspend fun leave()
    suspend fun complete()
    fun close()
}

data class StoredSession(
    val serverUrl: String,
    val groupId: String,
    val vehicleId: String,
    val accessToken: String,
)
