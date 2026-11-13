package com.meetroute.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meetroute.app.domain.GroupPhase
import com.meetroute.app.domain.MeetingCandidate
import com.meetroute.app.domain.RecommendationSet
import com.meetroute.app.domain.evPlanLine
import com.meetroute.app.domain.formatClock
import com.meetroute.app.domain.formatMinutes
import com.meetroute.app.domain.routeSourceLabel
import com.meetroute.app.ui.MeetRouteUiState
import com.meetroute.app.ui.MeetRouteViewModel
import com.meetroute.app.ui.Screen
import com.meetroute.app.ui.components.Banner
import com.meetroute.app.ui.components.Chip
import com.meetroute.app.ui.components.Metric
import com.meetroute.app.ui.components.PrimaryButton
import com.meetroute.app.ui.components.SecondaryButton
import com.meetroute.app.ui.components.Section
import com.meetroute.app.ui.components.WIDE_LAYOUT_MIN_WIDTH
import com.meetroute.app.ui.theme.Accent
import com.meetroute.app.ui.theme.Caution
import com.meetroute.app.ui.theme.Positive
import com.meetroute.app.ui.theme.TextMuted

@Composable
fun RecommendationScreen(state: MeetRouteUiState, viewModel: MeetRouteViewModel) {
    val recommendations = state.recommendations ?: return
    val backScreen = if (state.group?.phase == GroupPhase.EN_ROUTE) Screen.DRIVING else Screen.LOBBY
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("합류 장소 추천", style = MaterialTheme.typography.headlineMedium)
        state.message?.let { Banner(it) }
        state.dataLabel?.let { Banner(it) }
        Notices(recommendations)

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            if (maxWidth >= WIDE_LAYOUT_MIN_WIDTH) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                    recommendations.recommendations.forEach { candidate ->
                        CandidateCard(candidate, state, viewModel, Modifier.weight(1f))
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                    recommendations.recommendations.forEach { candidate ->
                        CandidateCard(candidate, state, viewModel, Modifier.fillMaxWidth())
                    }
                }
            }
        }
        if (recommendations.recommendations.none { it.isDestination }) {
            DestinationComparison(recommendations.destinationComparison)
        }
        Text(routeSourceLabel(recommendations.routeSource), color = TextMuted)
        SecondaryButton("돌아가기", { viewModel.open(backScreen) })
    }
}

@Composable
private fun Notices(recommendations: RecommendationSet) {
    if (recommendations.stale) Banner("최신 결과가 아닙니다. 다시 추천을 받아 주세요.")
    recommendations.notice?.let { Banner(it, Accent) }
    if (recommendations.windowExpanded) Banner("만남 목적의 시간대 안에 후보가 없어 범위를 넓혀 찾았습니다.")
    if (recommendations.excludedVehicles.isNotEmpty()) {
        Banner("위치를 공유하지 않은 차량 ${recommendations.excludedVehicles.size}대는 계산에서 제외했습니다.")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CandidateCard(
    candidate: MeetingCandidate,
    state: MeetRouteUiState,
    viewModel: MeetRouteViewModel,
    modifier: Modifier,
) {
    Section(modifier = modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${candidate.rank}순위", color = Accent, style = MaterialTheme.typography.titleMedium)
            if (candidate.isDestination) Chip("목적지 합류")
            if (candidate.hasEvCharger == true && candidate.evPlan != null) Chip("충전기", Positive)
        }
        Text(candidate.name, style = MaterialTheme.typography.titleLarge)
        candidate.direction?.let { Text("${candidate.routeName} · $it", color = TextMuted) }
        Text(candidate.reason, color = Accent)
        // 주행 중에는 판단에 필요한 정보만 남기고 상세 수치는 정차 상태에서 보여 준다.
        if (!state.isDriving) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric("합류 시각", formatClock(candidate.meetingTime), Modifier.weight(1f))
                Metric("최대 우회", formatMinutes(candidate.maxDetourMinutes), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric("대기 합계", formatMinutes(candidate.totalWaitMinutes), Modifier.weight(1f))
                Metric("동행 주행", formatMinutes(candidate.sharedMinutes), Modifier.weight(1f))
            }
            Facilities(candidate)
            ChargingPlan(candidate, state)
        }
        if (state.isLeader) {
            PrimaryButton("여기서 합류", { viewModel.selectMeetingPoint(candidate) }, enabled = !state.busy)
        }
    }
}

@Composable
private fun Facilities(candidate: MeetingCandidate) {
    val spaces = candidate.parkingSpaces ?: return
    Text(
        buildString {
            append("주차 ${spaces}면")
            if (candidate.hasRestaurant == true) append(" · 식당")
            if (candidate.hasEvCharger == true) append(" · EV 충전")
        },
        color = TextMuted,
    )
    // 공공데이터의 시설 정보이며 실시간 주차 가능 여부가 아니다.
    Text("공공데이터 ${candidate.dataDate.orEmpty()} 기준", color = TextMuted)
    if (candidate.dataUncertain) Chip("정보 불확실", Caution)
}

/** 전기차마다 이 장소에서의 충전 계획. 기다리는 시간과 겹치는 충전은 그룹을 늦추지 않는다. */
@Composable
private fun ChargingPlan(candidate: MeetingCandidate, state: MeetRouteUiState) {
    val plans = candidate.evPlan ?: return
    val participants = state.group?.participants.orEmpty()
    plans.forEach { (vehicleId, plan) ->
        val nickname = participants.find { it.vehicleId == vehicleId }?.nickname ?: "전기차"
        Text(evPlanLine(nickname, plan), color = if (plan.needsCharge && !plan.chargerAvailable) Caution else Positive)
    }
}

@Composable
private fun DestinationComparison(destination: MeetingCandidate) {
    Section("비교 기준: 목적지에서 바로 만나기") {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Metric("합류 시각", formatClock(destination.meetingTime), Modifier.weight(1f))
            Metric("대기 합계", formatMinutes(destination.totalWaitMinutes), Modifier.weight(1f))
            Metric("동행 주행", formatMinutes(destination.sharedMinutes), Modifier.weight(1f))
        }
    }
}
