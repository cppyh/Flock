package com.meetroute.app.navigation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val OLD = NavigationTarget("강릉역", 37.7640, 128.8996)
private val PICKED = NavigationTarget("현대백화점 판교점", 37.39192222, 127.11171666)

/** 조회할 때마다 정해 둔 답을 차례로 준다. 마지막 답은 계속 반복한다. */
private class ScriptedNavi(replies: List<Result<NavigationTarget?>>) : NavigationGateway by RecordingNavigationGateway() {
    private val queue = ArrayDeque(replies)

    override suspend fun lookupDestination(): Result<NavigationTarget?> =
        if (queue.size > 1) queue.removeFirst() else queue.first()
}

private fun some(target: NavigationTarget?) = Result.success(target)
private val failed = Result.failure<NavigationTarget?>(IllegalStateException("내비 응답 없음"))

@OptIn(ExperimentalCoroutinesApi::class)
class PlaceSearchTest {

    @Test
    fun `new destination is the picked place`() = runTest {
        val navi = ScriptedNavi(listOf(some(null), some(PICKED)))
        assertEquals(PICKED, awaitPickedDestination(navi, before = null))
    }

    @Test
    fun `picking while another route is guided waits through the rebuild`() = runTest {
        val navi = ScriptedNavi(listOf(some(OLD), some(null), some(null), some(PICKED)))
        assertEquals(PICKED, awaitPickedDestination(navi, before = OLD))
    }

    @Test
    fun `unchanged destination is not a pick`() = runTest {
        val navi = ScriptedNavi(listOf(some(OLD)))
        val result = async { awaitPickedDestination(navi, before = OLD) }
        advanceTimeBy(60_000)
        runCurrent()
        assertFalse(result.isCompleted)
        result.cancel()
    }

    @Test
    fun `picking the same place again is recognised after the rebuild`() = runTest {
        val navi = ScriptedNavi(listOf(some(OLD), some(null), some(OLD)))
        assertEquals(OLD, awaitPickedDestination(navi, before = OLD))
    }

    @Test
    fun `failed query is not taken for an empty destination`() = runTest {
        // 조회 한 번이 실패해도 기존 목적지를 고른 장소로 착각하면 안 된다.
        val navi = ScriptedNavi(listOf(failed, some(OLD)))
        val result = async { awaitPickedDestination(navi, before = OLD) }
        advanceTimeBy(60_000)
        runCurrent()
        assertFalse(result.isCompleted)
        result.cancel()
    }

    @Test
    fun `no pick ends with nothing after the timeout`() = runTest {
        assertNull(awaitPickedDestination(ScriptedNavi(listOf(some(null))), before = null))
    }

    @Test
    fun `pick waits until the destination may change`() = runTest {
        var allowed = false
        val navi = ScriptedNavi(listOf(some(PICKED)))
        val result = async { awaitPickedDestination(navi, before = null, canApply = { allowed }) }
        advanceTimeBy(30_000)
        runCurrent()
        assertFalse(result.isCompleted)

        allowed = true
        assertEquals(PICKED, result.await())
    }

    @Test
    fun `long address fits the server limit`() {
        val address = NavigationTarget("가".repeat(120), 37.4, 127.1)
        assertEquals(80, address.toDestination().name.length)
        assertTrue(PICKED.toDestination().name == PICKED.name)
    }
}
