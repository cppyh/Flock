package com.meetroute.app.domain

import com.meetroute.app.location.LocationFix
import com.meetroute.app.location.LocationSourceType
import com.meetroute.app.ui.Screen
import com.meetroute.app.ui.isPlanChanged
import com.meetroute.app.ui.screenAfterUpdate
import com.meetroute.app.ui.screenFor
import com.meetroute.app.ui.screenOnRecommendations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

private const val BASE_MILLIS = 1_800_000_000_000L
private val BASE_ISO = Instant.ofEpochMilli(BASE_MILLIS).toString()

private fun participant(
    id: String = "a",
    status: ParticipantStatus = ParticipantStatus.ACTIVE,
    timestamp: String? = BASE_ISO,
    lat: Double? = 37.5,
    eta: Double? = null,
    arrived: Boolean = false,
    online: Boolean = true,
) = Participant(
    vehicleId = id,
    nickname = "차량$id",
    role = "MEMBER",
    status = status,
    online = online,
    lat = lat,
    lon = 127.0,
    timestamp = timestamp,
    etaSeconds = eta,
    arrived = arrived,
)

private fun group(vararg participants: Participant, phase: GroupPhase = GroupPhase.EN_ROUTE, version: Int = 1) =
    GroupState(
        groupId = "g",
        name = "여행",
        inviteCode = "ABC234",
        participants = participants.toList(),
        phase = phase,
        version = version,
    )

private fun fix(lat: Double, seconds: Long) = LocationFix(
    lat = lat,
    lon = 127.0,
    timestampMillis = BASE_MILLIS + seconds * 1000,
    source = LocationSourceType.SIMULATED,
)

class GroupReducerTest {

    @Test
    fun `new participant is appended`() {
        val result = GroupReducer.reduce(group(participant("a")), SessionEvent.ParticipantUpdated(participant("b")))
        assertEquals(listOf("a", "b"), result.participants.map { it.vehicleId })
    }

    @Test
    fun `participant update replaces existing one`() {
        val update = participant("a", lat = 37.6)
        val result = GroupReducer.reduce(group(participant("a")), SessionEvent.ParticipantUpdated(update))
        assertEquals(37.6, result.participants.single().lat!!, 0.0)
    }

    @Test
    fun `older location does not overwrite newer one but presence does`() {
        val current = participant("a", lat = 37.6, timestamp = BASE_ISO)
        val older = participant(
            "a",
            lat = 37.1,
            timestamp = Instant.ofEpochMilli(BASE_MILLIS - 5000).toString(),
            online = false,
        )
        val result = GroupReducer.reduce(group(current), SessionEvent.ParticipantUpdated(older)).participants.single()
        assertEquals(37.6, result.lat!!, 0.0)
        assertFalse(result.online)
    }

    @Test
    fun `withdrawn location clears coordinates`() {
        val cleared = participant("a", status = ParticipantStatus.PAUSED, timestamp = null, lat = null)
        val result = GroupReducer.reduce(group(participant("a")), SessionEvent.ParticipantUpdated(cleared))
        assertEquals(null, result.participants.single().lat)
    }

    @Test
    fun `left participant is removed`() {
        val left = participant("b", status = ParticipantStatus.LEFT)
        val result = GroupReducer.reduce(group(participant("a"), participant("b")), SessionEvent.ParticipantUpdated(left))
        assertEquals(listOf("a"), result.participants.map { it.vehicleId })
    }

    @Test
    fun `older group version is ignored`() {
        val current = group(participant("a"), version = 5)
        val stale = group(version = 4)
        assertEquals(current, GroupReducer.reduce(current, SessionEvent.GroupUpdated(stale)))
    }

    @Test
    fun `resync snapshot does not overwrite a newer realtime location of the same version`() {
        val newer = participant("a", lat = 37.6)
        val snapshot = participant("a", lat = 37.1, timestamp = Instant.ofEpochMilli(BASE_MILLIS - 5000).toString())
        val result = GroupReducer.reduce(group(newer), SessionEvent.GroupUpdated(group(snapshot)))
        assertEquals(37.6, result.participants.single().lat!!, 0.0)
    }

    @Test
    fun `newer group version replaces participants`() {
        val current = group(participant("a", arrived = true), version = 1)
        val replanned = group(participant("a", arrived = false), version = 2)
        val result = GroupReducer.reduce(current, SessionEvent.GroupUpdated(replanned))
        assertFalse(result.participants.single().arrived)
    }

    @Test
    fun `delayed vehicle raises the group reroute flag`() {
        val update = participant("a").copy(delayed = true)
        val result = GroupReducer.reduce(group(participant("a")), SessionEvent.ParticipantUpdated(update))
        assertTrue(result.rerouteSuggested)
    }

    @Test
    fun `recovered vehicle lowers the group reroute flag`() {
        val delayed = group(participant("a").copy(delayed = true)).copy(rerouteSuggested = true)
        val result = GroupReducer.reduce(delayed, SessionEvent.ParticipantUpdated(participant("a")))
        assertFalse(result.rerouteSuggested)
    }

    @Test
    fun `leaving of the delayed vehicle lowers the group reroute flag`() {
        val delayed = group(participant("a"), participant("b").copy(delayed = true)).copy(rerouteSuggested = true)
        val left = participant("b", status = ParticipantStatus.LEFT)
        assertFalse(GroupReducer.reduce(delayed, SessionEvent.ParticipantUpdated(left)).rerouteSuggested)
    }

    @Test
    fun `session close marks closed phase`() {
        val result = GroupReducer.reduce(group(), SessionEvent.SessionClosed("COMPLETED"))
        assertEquals(GroupPhase.CLOSED, result.phase)
    }

    @Test
    fun `location older than twenty seconds shows as stale even without server event`() {
        val vehicle = participant()
        assertEquals(ParticipantStatus.ACTIVE, vehicle.effectiveStatus(BASE_MILLIS + 19_000))
        assertEquals(ParticipantStatus.STALE, vehicle.effectiveStatus(BASE_MILLIS + 20_000))
    }

    @Test
    fun `paused status is not overridden by age`() {
        val vehicle = participant(status = ParticipantStatus.PAUSED)
        assertEquals(ParticipantStatus.PAUSED, vehicle.effectiveStatus(BASE_MILLIS + 60_000))
    }
}

class LocationPublishPolicyTest {

    @Test
    fun `first fix is always sent`() {
        assertTrue(LocationPublishPolicy().shouldSend(fix(37.5, 0)))
    }

    @Test
    fun `moving vehicle sends about every five seconds`() {
        val policy = LocationPublishPolicy()
        policy.shouldSend(fix(37.5, 0))
        assertFalse(policy.shouldSend(fix(37.501, 4)))
        assertTrue(policy.shouldSend(fix(37.501, 5)))
    }

    @Test
    fun `stopped vehicle sends less often`() {
        val policy = LocationPublishPolicy()
        policy.shouldSend(fix(37.5, 0))
        assertFalse(policy.shouldSend(fix(37.5, 10)))
        assertFalse(policy.shouldSend(fix(37.5, 14)))
        assertTrue(policy.shouldSend(fix(37.5, 15)))
    }

    @Test
    fun `stopped vehicle reports before it would look disconnected`() {
        assertTrue(STOPPED_INTERVAL_MILLIS < STALE_AFTER_MILLIS)
    }

    @Test
    fun `force sends arrival immediately`() {
        val policy = LocationPublishPolicy()
        policy.shouldSend(fix(37.5, 0))
        assertTrue(policy.shouldSend(fix(37.5, 1), force = true))
    }

    @Test
    fun `reset starts a new sharing period`() {
        val policy = LocationPublishPolicy()
        policy.shouldSend(fix(37.5, 0))
        policy.reset()
        assertTrue(policy.shouldSend(fix(37.5, 1)))
    }
}

class DrivingStateDetectorTest {

    @Test
    fun `stationary vehicle is not driving`() {
        val detector = DrivingStateDetector()
        assertFalse(detector.update(fix(37.5, 0)))
        assertFalse(detector.update(fix(37.5, 5)))
    }

    @Test
    fun `moving vehicle is driving`() {
        val detector = DrivingStateDetector()
        detector.update(fix(37.5, 0))
        assertTrue(detector.update(fix(37.501, 5)))
    }

    @Test
    fun `brief stop keeps driving state and long stop clears it`() {
        val detector = DrivingStateDetector()
        detector.update(fix(37.5, 0))
        detector.update(fix(37.501, 5))
        assertTrue(detector.update(fix(37.501, 10)))
        assertFalse(detector.update(fix(37.501, 16)))
    }
}

class GroupAnnouncerTest {

    private val announcer = GroupAnnouncer()

    @Test
    fun `nothing is announced on first state`() {
        assertTrue(announcer.announcements(null, group(participant("a")), "a").isEmpty())
    }

    @Test
    fun `eta crossing ten minutes is announced once`() {
        val before = group(participant("b", eta = 700.0))
        val after = group(participant("b", eta = 590.0))
        assertEquals(listOf("차량b 차량이 합류 지점까지 약 10분 남았습니다."), announcer.announcements(before, after, "a"))
        assertTrue(announcer.announcements(after, group(participant("b", eta = 500.0)), "a").isEmpty())
    }

    @Test
    fun `my own vehicle is not announced`() {
        val before = group(participant("a", eta = 700.0))
        val after = group(participant("a", eta = 500.0))
        assertTrue(announcer.announcements(before, after, "a").isEmpty())
    }

    @Test
    fun `arrival and all arrived are announced`() {
        val before = group(participant("a", arrived = true), participant("b"))
        val after = group(participant("a", arrived = true), participant("b", arrived = true), phase = GroupPhase.ALL_ARRIVED)
        assertEquals(
            listOf("차량b 차량이 합류 장소에 도착했습니다.", "모든 차량이 합류 장소에 도착했습니다."),
            announcer.announcements(before, after, "a"),
        )
    }

    @Test
    fun `reroute suggestion is announced`() {
        val before = group(participant("a"))
        val after = before.copy(rerouteSuggested = true)
        assertEquals(listOf("새로운 합류 장소를 추천할 수 있습니다."), announcer.announcements(before, after, "a"))
    }

    private fun point(placeId: String, name: String) = MeetingCandidate(
        placeId = placeId,
        name = name,
        latitude = 37.6,
        longitude = 128.4,
        meetingTime = BASE_ISO,
        totalDetourMinutes = 0.0,
        totalWaitMinutes = 0.0,
        maxDetourMinutes = 0.0,
        maxWaitMinutes = 0.0,
        sharedMinutes = 0.0,
    )

    @Test
    fun `first meeting point is announced`() {
        val before = group(participant("a"))
        val after = before.copy(selectedMeetingPointId = "p", meetingPoint = point("p", "평창(강릉)"))
        assertEquals(listOf("합류 장소가 정해졌습니다. 평창(강릉)입니다."), announcer.announcements(before, after, "a"))
    }

    @Test
    fun `changed meeting point is announced`() {
        val before = group(participant("a")).copy(selectedMeetingPointId = "p", meetingPoint = point("p", "평창(강릉)"))
        val after = before.copy(selectedMeetingPointId = "q", meetingPoint = point("q", "횡성(강릉)"))
        assertEquals(listOf("합류 장소가 변경되었습니다. 횡성(강릉)입니다."), announcer.announcements(before, after, "a"))
    }

    private val hiddenDrive = FreeDrive(planId = "p1", hours = 2, theme = DriveTheme.FOREST, isPrivate = true, revealed = false)

    @Test
    fun `private destination is announced to everyone when revealed`() {
        val before = group(participant("a", arrived = true), participant("b"))
            .copy(destinationHidden = true, freeDrive = hiddenDrive)
        val after = group(
            participant("a", arrived = true),
            participant("b", arrived = true),
            phase = GroupPhase.ALL_ARRIVED,
            version = 2,
        ).copy(destination = Destination("남이섬", 37.79, 127.52), freeDrive = hiddenDrive.copy(revealed = true))
        val messages = announcer.announcements(before, after, "a")
        assertEquals("오늘의 목적지는 남이섬입니다.", messages.last())
        assertTrue("모든 차량이 합류 장소에 도착했습니다." in messages)
    }

    @Test
    fun `public drive has no reveal announcement`() {
        val drive = hiddenDrive.copy(isPrivate = false, revealed = true)
        val before = group(participant("a")).copy(destination = Destination("남이섬", 37.79, 127.52), freeDrive = drive)
        assertTrue(announcer.announcements(before, before.copy(version = 2), "a").isEmpty())
    }

    private fun plan(needs: Boolean, charger: Boolean = true, minutes: Double = 18.0, bonus: Double = 0.0) = EvPlan(
        batteryPercent = 30.0,
        batteryAtArrival = 12.0,
        batteryAtDestination = 2.0,
        needsCharge = needs,
        chargerAvailable = charger,
        chargeMinutes = minutes,
        coveredByWaitMinutes = 10.0,
        extraWaitMinutes = 8.0,
        bonusPercent = bonus,
    )

    @Test
    fun `my electric vehicle is reminded to charge on arrival`() {
        val meeting = point("p", "평창(강릉)").copy(evPlan = mapOf("a" to plan(needs = true)))
        val before = group(participant("a")).copy(selectedMeetingPointId = "p", meetingPoint = meeting)
        val after = group(participant("a", arrived = true)).copy(selectedMeetingPointId = "p", meetingPoint = meeting)
        assertEquals(listOf("기다리는 동안 충전하세요. 약 18분 필요합니다."), announcer.announcements(before, after, "a"))
        // 도착이 이미 알려진 뒤에는 다시 말하지 않는다.
        assertTrue(announcer.announcements(after, after, "a").isEmpty())
    }

    @Test
    fun `no charging reminder without a charger or a need`() {
        val noCharger = point("p", "휴게소").copy(evPlan = mapOf("a" to plan(needs = true, charger = false)))
        val before = group(participant("a")).copy(selectedMeetingPointId = "p", meetingPoint = noCharger)
        val after = group(participant("a", arrived = true)).copy(selectedMeetingPointId = "p", meetingPoint = noCharger)
        assertTrue(announcer.announcements(before, after, "a").isEmpty())
        val full = point("p", "휴게소").copy(evPlan = mapOf("a" to plan(needs = false, minutes = 0.0)))
        val fullBefore = group(participant("a")).copy(selectedMeetingPointId = "p", meetingPoint = full)
        val fullAfter = group(participant("a", arrived = true)).copy(selectedMeetingPointId = "p", meetingPoint = full)
        assertTrue(announcer.announcements(fullBefore, fullAfter, "a").isEmpty())
    }
}

class FormattersTest {

    @Test
    fun `minutes are readable`() {
        assertEquals("6분", formatMinutes(6.2))
        assertEquals("1시간", formatMinutes(60.0))
        assertEquals("1시간 15분", formatMinutes(75.0))
        assertEquals("0분", formatMinutes(0.0))
    }

    @Test
    fun `eta without value says not computed`() {
        assertEquals("계산 전", formatEta(null))
        assertEquals("10분", formatEta(600.0))
    }

    @Test
    fun `clock uses given zone`() {
        assertEquals("09:30", formatClock("2026-11-07T09:30:00Z", ZoneOffset.UTC))
    }

    @Test
    fun `elapsed text tracks last update`() {
        assertEquals("기록 없음", formatElapsed(null, BASE_MILLIS))
        assertEquals("방금", formatElapsed(BASE_ISO, BASE_MILLIS + 2000))
        assertEquals("30초 전", formatElapsed(BASE_ISO, BASE_MILLIS + 30_000))
        assertEquals("2분 전", formatElapsed(BASE_ISO, BASE_MILLIS + 120_000))
    }

    @Test
    fun `simulated route data is labelled`() {
        assertTrue(routeSourceLabel("ESTIMATE").contains("모의 데이터"))
        assertTrue(routeSourceLabel("OSRM").contains("실시간 교통 미반영"))
    }

    @Test
    fun `drive summary names the theme and round trip time`() {
        val drive = FreeDrive(hours = 2, theme = DriveTheme.SEA, roundTripMinutes = 125.0)
        assertEquals("바다 · 왕복 약 2시간 5분", freeDriveSummary(drive))
        assertEquals("완전 랜덤", freeDriveSummary(FreeDrive(hours = 1)))
    }

    private fun evPlan(needs: Boolean, charger: Boolean, extra: Double, bonus: Double = 0.0) = EvPlan(
        batteryPercent = 30.0,
        batteryAtArrival = -3.0,
        batteryAtDestination = -10.0,
        needsCharge = needs,
        chargerAvailable = charger,
        chargeMinutes = 18.0,
        coveredByWaitMinutes = 12.0,
        extraWaitMinutes = extra,
        bonusPercent = bonus,
    )

    @Test
    fun `charging plan line explains how the wait is used`() {
        assertEquals("수원: 도착 시 0% · 충전 18분 중 12분 대기와 겹침", evPlanLine("수원", evPlan(true, true, 6.0)))
        assertEquals("수원: 도착 시 0% · 충전 18분, 대기 중 끝남", evPlanLine("수원", evPlan(true, true, 0.0)))
        assertEquals("수원: 도착 시 0% · 충전소 없음, 따로 충전 28분", evPlanLine("수원", evPlan(true, false, 28.0)))
        assertEquals("수원: 도착 시 0% · 대기 중 +15% 충전 가능", evPlanLine("수원", evPlan(false, true, 0.0, bonus = 15.0)))
        assertEquals("배터리 43%", batteryLabel(42.6))
    }
}

class ScreenFlowTest {

    @Test
    fun `selected meeting point moves to driving screen`() {
        assertEquals(Screen.DRIVING, screenFor(group(phase = GroupPhase.EN_ROUTE), Screen.LOBBY))
    }

    @Test
    fun `re-recommendation while driving stays on recommendation screen`() {
        assertEquals(Screen.RECOMMENDATIONS, screenFor(group(phase = GroupPhase.EN_ROUTE), Screen.RECOMMENDATIONS))
    }

    @Test
    fun `everyone arrived moves to completion`() {
        assertEquals(Screen.COMPLETED, screenFor(group(phase = GroupPhase.ALL_ARRIVED), Screen.DRIVING))
    }

    @Test
    fun `destination change returns to lobby`() {
        assertEquals(Screen.LOBBY, screenFor(group(phase = GroupPhase.FORMING), Screen.DRIVING))
    }

    @Test
    fun `member viewing recommendations follows leader selection`() {
        val before = group(phase = GroupPhase.FORMING)
        val after = before.copy(phase = GroupPhase.EN_ROUTE, selectedMeetingPointId = "평창(강릉)")
        assertEquals(Screen.DRIVING, screenAfterUpdate(before, after, Screen.RECOMMENDATIONS))
    }

    @Test
    fun `member viewing recommendations returns to lobby when destination changes`() {
        val before = group(phase = GroupPhase.FORMING).copy(destination = Destination("강릉역", 37.7, 128.8))
        val after = before.copy(destination = Destination("부산역", 35.1, 129.0), version = 2)
        assertTrue(isPlanChanged(before, after))
        assertEquals(Screen.LOBBY, screenAfterUpdate(before, after, Screen.RECOMMENDATIONS))
    }

    @Test
    fun `changed meal time invalidates the plan`() {
        val before = group(phase = GroupPhase.FORMING).copy(mealTime = "2026-11-07T03:00:00Z")
        val after = before.copy(mealTime = "2026-11-07T04:00:00Z", version = 2)
        assertTrue(isPlanChanged(before, after))
    }

    @Test
    fun `location updates keep member on recommendation screen`() {
        val before = group(participant("a"), phase = GroupPhase.FORMING)
        val after = group(participant("a", lat = 37.6), phase = GroupPhase.FORMING)
        assertFalse(isPlanChanged(before, after))
        assertEquals(Screen.RECOMMENDATIONS, screenAfterUpdate(before, after, Screen.RECOMMENDATIONS))
    }

    @Test
    fun `new hidden course invalidates the plan of a member`() {
        val drive = FreeDrive(planId = "p1", hours = 2, isPrivate = true, revealed = false)
        val before = group(phase = GroupPhase.FORMING).copy(destinationHidden = true, freeDrive = drive)
        val after = before.copy(freeDrive = drive.copy(planId = "p2"), version = 2)
        assertTrue(isPlanChanged(before, after))
        assertEquals(Screen.LOBBY, screenAfterUpdate(before, after, Screen.RECOMMENDATIONS))
    }

    @Test
    fun `reveal at the meeting point moves everyone to the completion screen`() {
        val drive = FreeDrive(planId = "p1", hours = 2, isPrivate = true, revealed = false)
        val before = group(phase = GroupPhase.EN_ROUTE).copy(destinationHidden = true, freeDrive = drive)
        val after = before.copy(
            phase = GroupPhase.ALL_ARRIVED,
            destinationHidden = false,
            destination = Destination("남이섬", 37.79, 127.52),
            freeDrive = drive.copy(revealed = true),
            version = 2,
        )
        assertEquals(Screen.COMPLETED, screenAfterUpdate(before, after, Screen.DRIVING))
    }

    @Test
    fun `new recommendations open from lobby but never interrupt driving`() {
        assertEquals(Screen.RECOMMENDATIONS, screenOnRecommendations(Screen.LOBBY))
        assertEquals(Screen.DRIVING, screenOnRecommendations(Screen.DRIVING))
    }
}
