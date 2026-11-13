package com.meetroute.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meetroute.app.domain.GroupState
import com.meetroute.app.domain.formatClock
import com.meetroute.app.domain.formatEta
import com.meetroute.app.domain.formatMinutes
import com.meetroute.app.domain.freeDriveSummary
import kotlin.math.roundToInt
import com.meetroute.app.ui.MeetRouteUiState
import com.meetroute.app.ui.MeetRouteViewModel
import com.meetroute.app.ui.NavigationUiState
import com.meetroute.app.ui.components.AdaptivePanes
import com.meetroute.app.ui.components.Banner
import com.meetroute.app.ui.components.Chip
import com.meetroute.app.ui.components.DangerButton
import com.meetroute.app.ui.components.GroupMap
import com.meetroute.app.ui.components.LabeledRow
import com.meetroute.app.ui.components.PrimaryButton
import com.meetroute.app.ui.components.SecondaryButton
import com.meetroute.app.ui.components.Section
import com.meetroute.app.ui.theme.Accent
import com.meetroute.app.ui.theme.Caution
import com.meetroute.app.ui.theme.Danger
import com.meetroute.app.ui.theme.Positive
import com.meetroute.app.ui.theme.TextMuted

@Composable
fun DrivingScreen(state: MeetRouteUiState, viewModel: MeetRouteViewModel, onRequestSharing: () -> Unit) {
    val group = state.group ?: return
    val point = group.meetingPoint ?: return
    AdaptivePanes(
        modifier = Modifier.padding(24.dp),
        first = {
            state.dataLabel?.let { Banner(it) }
            Section {
                Text("합류 지점", color = TextMuted)
                Text(point.name, style = MaterialTheme.typography.headlineMedium, color = Accent)
                Text("합류 예상 ${formatClock(point.meetingTime)}", style = MaterialTheme.typography.titleMedium)
                state.navRemainSeconds?.let { Text("내비 기준 남은 시간 ${formatEta(it.toDouble())}") }
                FinalDestinationLine(group)
            }
            MyChargingPlan(state)
            NavigationSection(state, viewModel)
            if (group.rerouteSuggested) {
                Banner("일부 차량이 지연되고 있습니다.", Caution)
                SecondaryButton("합류 장소 다시 찾기", viewModel::requestRecommendations, enabled = !state.busy)
            }
            state.message?.let { Banner(it) }
            ConsentSection(state, viewModel, onRequestSharing)
        },
        second = {
            VehicleList(group, state.myVehicleId, state.nowMillis)
            // 주행 중에는 ETA 중심으로, 정차 중에는 지도까지 보여 준다(기획서 F05).
            if (!state.isDriving) {
                GroupMap(group, state.myVehicleId, Modifier.fillMaxWidth().height(240.dp))
                VoiceSection(state, viewModel)
                LabeledRow("연결 상태", state.connection.label)
                state.locationSource?.let { LabeledRow("위치 출처", it.label) }
                SecondaryButton("합류 장소 다시 찾기", viewModel::requestRecommendations, enabled = !state.busy)
                if (state.isLeader) {
                    DangerButton("세션 종료", viewModel::completeSession, enabled = !state.busy)
                } else {
                    SecondaryButton("그룹 나가기", viewModel::leaveGroup, enabled = !state.busy)
                }
            }
        },
    )
}

@Composable
private fun NavigationSection(state: MeetRouteUiState, viewModel: MeetRouteViewModel) {
    Section("내비게이션") {
        NavigationStatusText(state.navigation)
        Chip(state.navigationLabel.ifEmpty { "연결 확인 중" })
        val navigation = state.navigation
        if (navigation is NavigationUiState.ConfirmDestination) {
            DestinationConfirmation(viewModel)
        } else {
            val group = state.group
            PrimaryButton(
                when {
                    group?.meetingPoint?.isDestination == true -> "내비에 목적지로 등록"
                    // 비공개 드라이브의 그룹원은 합류 지점까지만 안내받는다.
                    group?.destinationHidden == true -> "내비에 합류 지점 등록"
                    else -> "내비에 경유지로 등록"
                },
                viewModel::applyToNavigation,
                enabled = navigation != NavigationUiState.Requesting,
            )
        }
        if (navigation == NavigationUiState.Applied || navigation == NavigationUiState.Unconfirmed) {
            SecondaryButton("내비 화면 열기", viewModel::openNavigationScreen)
            SecondaryButton("내비 경로 재탐색", viewModel::rerouteNavigation)
        }
        // 서비스가 늦게 뜬 경우 앱을 다시 켜지 않고 실제 내비로 돌아갈 수 있게 한다.
        if (state.navigationLabel.isNotEmpty() && !state.navigationConnected) {
            SecondaryButton("내비 다시 연결", viewModel::connectNavigation)
        }
    }
}

@Composable
private fun FinalDestinationLine(group: GroupState) {
    when {
        group.destinationHidden -> Text("최종 목적지는 모두 합류하면 공개됩니다", color = TextMuted)
        group.freeDrive != null -> group.destination?.let { Text("드라이브 목적지 ${it.name}", color = TextMuted) }
    }
}

/** 내 전기차가 합류 지점에서 충전해야 하면 기다리는 시간을 충전에 쓰도록 미리 알린다. */
@Composable
private fun MyChargingPlan(state: MeetRouteUiState) {
    val myId = state.myVehicleId ?: return
    val plan = state.group?.meetingPoint?.evPlan?.get(myId) ?: return
    when {
        plan.needsCharge && plan.chargerAvailable -> Banner(
            "합류 지점에서 기다리는 동안 약 ${formatMinutes(plan.chargeMinutes)} 충전하세요. " +
                "도착 시 배터리 약 ${plan.batteryAtArrival.coerceAtLeast(0.0).roundToInt()}%",
            Accent,
        )
        plan.needsCharge -> Banner("합류 지점에 충전기가 없습니다. 목적지까지 가려면 따로 충전해야 합니다.", Caution)
        plan.bonusPercent >= 5 -> Banner("합류 지점에 충전기가 있습니다. 기다리는 동안 약 ${plan.bonusPercent.roundToInt()}% 충전할 수 있습니다.", Positive)
    }
}

@Composable
private fun DestinationConfirmation(viewModel: MeetRouteViewModel) {
    PrimaryButton("목적지를 바꾸고 등록", viewModel::confirmDestinationChange)
    SecondaryButton("취소", viewModel::cancelDestinationChange)
}

@Composable
private fun NavigationStatusText(navigation: NavigationUiState) {
    when (navigation) {
        NavigationUiState.Idle -> Text("운전자가 확인한 뒤 등록합니다.", color = TextMuted)
        NavigationUiState.Requesting -> Text("내비가 경로를 계산하고 있습니다. 1분 가까이 걸릴 수 있습니다.", color = TextMuted)
        NavigationUiState.Applied -> Text("내비게이션에 등록했습니다.", color = Positive)
        NavigationUiState.RecordedOnly -> Text("실제 내비게이션이 없어 요청만 기록했습니다.", color = Caution)
        NavigationUiState.Unconfirmed -> Text("경로 탐색은 끝났지만 등록을 확인하지 못했습니다. 내비 화면에서 확인해 주세요.", color = Caution)
        is NavigationUiState.ConfirmDestination -> Text(
            "내비가 ${navigation.current}(으)로 안내 중입니다. 그룹 목적지 ${navigation.requested}(으)로 바꿀까요? " +
                "내비에 넣어 둔 경유지는 지워질 수 있습니다.",
            color = Caution,
        )
        is NavigationUiState.Failed -> Text(navigation.message, color = Danger)
    }
}

@Composable
private fun VoiceSection(state: MeetRouteUiState, viewModel: MeetRouteViewModel) {
    Section {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                if (state.voiceEnabled && !state.voiceAvailable) "음성 안내(이 기기에서 사용 불가)" else "음성 안내",
                modifier = Modifier.weight(1f),
            )
            Switch(checked = state.voiceEnabled, onCheckedChange = viewModel::setVoiceEnabled)
        }
    }
}

@Composable
fun CompletedScreen(state: MeetRouteUiState, viewModel: MeetRouteViewModel) {
    val group = state.group
    // 그룹장이 도착 전에 세션을 끝낼 수 있다. 만나지 못했는데 만났다고 표시하면 안 된다.
    val everyoneArrived = group != null && group.participants.isNotEmpty() && group.participants.all { it.arrived }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            if (everyoneArrived) "합류 완료" else "세션 종료",
            style = MaterialTheme.typography.headlineLarge,
            color = if (everyoneArrived) Positive else TextMuted,
        )
        if (group == null) {
            Text("세션이 종료되었습니다.")
            PrimaryButton("처음으로", viewModel::backToHome)
            return@Column
        }
        if (everyoneArrived) {
            group.meetingPoint?.let { Text("${it.name}에서 만났습니다.", style = MaterialTheme.typography.titleLarge) }
        }
        val drive = group.freeDrive
        val destination = group.destination
        if (drive != null && drive.isPrivate && drive.revealed && destination != null) {
            // 비공개 드라이브의 공개 순간. 모든 차량 화면에 같은 목적지가 뜬다.
            Section {
                Text("오늘의 목적지", color = TextMuted)
                Text(destination.name, style = MaterialTheme.typography.headlineLarge, color = Accent)
                Text(freeDriveSummary(drive), style = MaterialTheme.typography.titleMedium)
                val detail = listOfNotNull(drive.region, drive.description).joinToString(" · ")
                if (detail.isNotEmpty()) Text(detail, color = TextMuted)
            }
        } else {
            destination?.let { Text("최종 목적지: ${it.name}") }
            if (group.destinationHidden) Text("최종 목적지는 모두 합류하면 공개됩니다.", color = TextMuted)
        }
        Section("차량별 도착") {
            group.participants.forEach { vehicle ->
                LabeledRow(vehicle.nickname, if (vehicle.arrived) "도착" else "미확인")
            }
        }
        state.message?.let { Banner(it) }
        if (state.sessionOpen) {
            NavigationStatusText(state.navigation)
            if (state.navigation is NavigationUiState.ConfirmDestination) {
                DestinationConfirmation(viewModel)
            } else {
                PrimaryButton(
                    "목적지까지 그룹 이동 계속",
                    viewModel::continueToDestination,
                    enabled = state.navigation != NavigationUiState.Requesting,
                )
            }
            if (state.isLeader) {
                DangerButton("세션 종료", viewModel::completeSession, enabled = !state.busy)
            } else {
                SecondaryButton("그룹 나가기", viewModel::leaveGroup, enabled = !state.busy)
            }
        } else {
            Text("위치 공유가 종료되었습니다.", color = TextMuted)
            PrimaryButton("처음으로", viewModel::backToHome)
        }
    }
}
