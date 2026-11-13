package com.meetroute.app.navigation

import com.meetroute.app.domain.Destination
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

// SDK에는 장소 검색 결과를 돌려주는 기능이 없다. 내비의 검색 화면을 열고 고른 결과를 목적지로 읽는다.
private const val PICK_POLL_MILLIS = 3_000L

// 검색어를 고쳐 가며 고르는 시간을 넉넉히 둔다.
private const val PICK_TIMEOUT_MILLIS = 300_000L

// 서버의 목적지 이름 길이 제한과 같아야 한다(backend/app/models/schemas.py). 주소는 길 수 있다.
private const val MAX_DESTINATION_NAME_LENGTH = 80

fun NavigationTarget.toDestination() = Destination(name.take(MAX_DESTINATION_NAME_LENGTH), lat, lon)

private fun NavigationTarget.isChangedFrom(other: NavigationTarget): Boolean =
    name != other.name || lat != other.lat || lon != other.lon

/**
 * 내비에서 장소를 고를 때까지 기다린다. 시간 안에 고르지 않으면 null이다.
 *
 * 고르면 내비의 목적지가 바뀐다. 같은 장소를 다시 고르면 값은 그대로지만 경로를 다시 만드는 동안
 * 목적지가 비었다가 돌아오므로 그것도 고른 것으로 본다. 조회 실패는 빈 값이 아니라 없던 일로 친다.
 *
 * @param before 검색을 열기 전의 목적지
 * @param canApply false인 동안은 골랐어도 넘겨주지 않고 기다린다
 */
suspend fun awaitPickedDestination(
    navigation: NavigationGateway,
    before: NavigationTarget?,
    canApply: () -> Boolean = { true },
    pollMillis: Long = PICK_POLL_MILLIS,
    timeoutMillis: Long = PICK_TIMEOUT_MILLIS,
): NavigationTarget? = withTimeoutOrNull(timeoutMillis) { pollForPick(navigation, before, canApply, pollMillis) }

private suspend fun pollForPick(
    navigation: NavigationGateway,
    before: NavigationTarget?,
    canApply: () -> Boolean,
    pollMillis: Long,
): NavigationTarget {
    var known = before
    while (true) {
        delay(pollMillis)
        val result = navigation.lookupDestination()
        if (result.isFailure) continue
        val current = result.getOrNull()
        if (current == null) {
            known = null
        } else if ((known == null || current.isChangedFrom(known)) && canApply()) {
            return current
        }
    }
}
