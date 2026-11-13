package com.meetroute.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.meetroute.app.domain.Destination
import com.meetroute.app.domain.GroupState
import com.meetroute.app.domain.MeetingPurpose
import com.meetroute.app.domain.Participant
import com.meetroute.app.domain.batteryLabel
import com.meetroute.app.domain.effectiveStatus
import com.meetroute.app.domain.etaSourceLabel
import com.meetroute.app.domain.formatElapsed
import com.meetroute.app.domain.formatEta
import com.meetroute.app.ui.MeetRouteUiState
import com.meetroute.app.ui.MeetRouteViewModel
import com.meetroute.app.ui.components.AdaptivePanes
import com.meetroute.app.ui.components.Banner
import com.meetroute.app.ui.components.Chip
import com.meetroute.app.ui.components.GroupMap
import com.meetroute.app.ui.components.PrimaryButton
import com.meetroute.app.ui.components.SecondaryButton
import com.meetroute.app.ui.components.Section
import com.meetroute.app.ui.components.StatusChip
import com.meetroute.app.ui.theme.Accent
import com.meetroute.app.ui.theme.Caution
import com.meetroute.app.ui.theme.Danger
import com.meetroute.app.ui.theme.Positive
import com.meetroute.app.ui.theme.TextMuted

/** POI 검색 제공자를 정하기 전까지 쓰는 주요 여행지 목록. 내비 목적지 가져오기도 함께 제공한다. */
private val DESTINATION_PRESETS = listOf(
    Destination("강릉역", 37.7640, 128.8996),
    Destination("속초시외버스터미널", 38.2047, 128.5912),
    Destination("부산역", 35.1152, 129.0422),
    Destination("여수엑스포역", 34.7528, 127.7498),
    Destination("경주역", 35.8440, 129.2190),
    Destination("전주역", 35.8499, 127.1616),
)

private val MEAL_OFFSETS_MINUTES = listOf(60L, 120L, 180L)
private const val SEARCH_KEYWORD_LENGTH = 60

// 서버의 예비 잔량(20%)보다 낮으면 위험, 그 두 배보다 낮으면 주의로 표시한다.
private const val LOW_BATTERY_PERCENT = 20.0
private const val MID_BATTERY_PERCENT = 40.0

@Composable
fun LobbyScreen(
    state: MeetRouteUiState,
    viewModel: MeetRouteViewModel,
    onRequestSharing: () -> Unit,
) {
    val group = state.group ?: return
    AdaptivePanes(
        modifier = Modifier.padding(24.dp),
        first = {
            GroupHeader(state, group)
            ConsentSection(state, viewModel, onRequestSharing)
            VehicleList(group, state.myVehicleId, state.nowMillis)
            if (!state.isDriving) SecondaryButton("그룹 나가기", viewModel::leaveGroup, enabled = !state.busy)
        },
        second = {
            state.message?.let { Banner(it) }
            if (state.isDriving) {
                Banner("주행 중에는 목적지와 만남 목적을 바꿀 수 없습니다.")
            } else {
                DestinationSection(state, group, viewModel)
                PurposeSection(state, group, viewModel)
            }
            // 드라이브 설정이 길어져도 다음 행동이 화면 안에 남도록 지도보다 먼저 둔다.
            PrimaryButton(
                "합류 장소 추천받기",
                viewModel::requestRecommendations,
                enabled = group.hasDestination && !state.busy,
            )
            GroupMap(group, state.myVehicleId, Modifier.fillMaxWidth().height(240.dp))
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupHeader(state: MeetRouteUiState, group: GroupState) {
    Section {
        Text(group.name, style = MaterialTheme.typography.titleLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("초대 코드", color = TextMuted)
            Text(group.inviteCode, style = MaterialTheme.typography.headlineMedium, color = Accent, softWrap = false)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("${group.participants.size}/${group.maxVehicles}대")
            Chip(state.connection.label)
        }
        state.dataLabel?.let { Banner(it) }
    }
}

/** 공유 중지는 언제든 할 수 있어야 하므로 대기실과 주행 화면에서 함께 쓴다(기획서 §9.1). */
@Composable
fun ConsentSection(
    state: MeetRouteUiState,
    viewModel: MeetRouteViewModel,
    onRequestSharing: () -> Unit,
) {
    Section("위치 공유") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                if (state.isSharing) {
                    "공유 중입니다. 언제든 끌 수 있습니다."
                } else {
                    "그룹 참여 차량에게 내 위치를 공유합니다. 전기차는 배터리 잔량도 함께 공유합니다."
                },
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = state.isSharing,
                enabled = !state.busy,
                onCheckedChange = { enabled ->
                    if (enabled) onRequestSharing() else viewModel.setLocationSharing(false)
                },
            )
        }
        state.locationSource?.let { Text("위치 출처: ${it.label}", color = TextMuted) }
        state.locationProblem?.let { Banner(it) }
        state.sharingNotice?.let { Banner(it) }
    }
}

@Composable
fun VehicleList(group: GroupState, myVehicleId: String?, nowMillis: Long) {
    Section("참여 차량") {
        group.participants.forEach { vehicle ->
            VehicleRow(vehicle, vehicle.vehicleId == myVehicleId, nowMillis)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VehicleRow(vehicle: Participant, isMe: Boolean, nowMillis: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                buildString {
                    append(vehicle.nickname)
                    if (isMe) append(" (나)")
                    if (vehicle.isLeader) append(" · 그룹장")
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            StatusChip(vehicle.effectiveStatus(nowMillis))
        }
        // 좁은 창에서 시뮬레이션 표시가 밀려 사라지면 안 된다(기획서 §11.3). 줄을 바꿔서라도 보여 준다.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (vehicle.arrived) "도착" else "남은 시간 ${formatEta(vehicle.etaSeconds)}",
                color = if (vehicle.delayed) Caution else TextMuted,
            )
            val source = etaSourceLabel(vehicle.etaSource)
            if (source.isNotEmpty()) Text(source, color = TextMuted)
            Text("갱신 ${formatElapsed(vehicle.timestamp, nowMillis)}", color = TextMuted)
            vehicle.batteryPercent?.let { percent ->
                val color = when {
                    percent < LOW_BATTERY_PERCENT -> Danger
                    percent < MID_BATTERY_PERCENT -> Caution
                    else -> Positive
                }
                Chip(batteryLabel(percent), color)
            }
            if (vehicle.isSimulated) Chip("시뮬레이션", Caution)
        }
    }
}

private enum class DestinationMode { PLACE, FREE_DRIVE }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DestinationSection(state: MeetRouteUiState, group: GroupState, viewModel: MeetRouteViewModel) {
    Section("공통 목적지") {
        if (!state.isLeader) {
            if (group.freeDrive != null) {
                FreeDriveSummary(group)
            } else {
                Text(group.destination?.name ?: "아직 정하지 않았습니다", style = MaterialTheme.typography.titleMedium)
            }
            Text("그룹장이 정합니다.", color = TextMuted)
            return@Section
        }
        // 시연 모드의 추천은 녹화된 목적지 기준이라 다른 곳을 고를 수 없다.
        val canChoose = state.dataLabel == null
        var mode by rememberSaveable {
            mutableStateOf(if (group.freeDrive != null) DestinationMode.FREE_DRIVE else DestinationMode.PLACE)
        }
        if (canChoose) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == DestinationMode.PLACE,
                    onClick = { mode = DestinationMode.PLACE },
                    label = { Text("목적지 정하기") },
                )
                FilterChip(
                    selected = mode == DestinationMode.FREE_DRIVE,
                    onClick = { mode = DestinationMode.FREE_DRIVE },
                    label = { Text("목적지 없이 드라이브") },
                )
            }
        }
        if (canChoose && mode == DestinationMode.FREE_DRIVE) {
            FreeDriveSetup(state, group, viewModel)
            return@Section
        }
        Text(group.destination?.name ?: "아직 정하지 않았습니다", style = MaterialTheme.typography.titleMedium)
        if (canChoose) DestinationSearch(state, viewModel)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DESTINATION_PRESETS.forEach { preset ->
                FilterChip(
                    selected = group.destination?.name == preset.name,
                    onClick = { viewModel.setDestination(preset) },
                    label = { Text(preset.name) },
                    enabled = !state.busy,
                )
            }
        }
        SecondaryButton("내비 목적지 가져오기", viewModel::importNavigationDestination, enabled = !state.busy)
    }
}

/** 장소 이름이나 주소를 내비의 검색 화면으로 넘긴다. 고른 결과는 앱이 그룹 목적지로 가져온다. */
@Composable
private fun DestinationSearch(state: MeetRouteUiState, viewModel: MeetRouteViewModel) {
    val query = state.destinationSearch
    if (query != null) {
        Banner("내비에서 '$query' 검색 결과를 고르면 그룹 목적지로 가져옵니다.", Accent)
        SecondaryButton("검색 취소", viewModel::cancelDestinationSearch)
        return
    }
    var keyword by rememberSaveable { mutableStateOf("") }
    val canSearch = keyword.isNotBlank() && !state.busy
    OutlinedTextField(
        value = keyword,
        onValueChange = { keyword = it.take(SEARCH_KEYWORD_LENGTH) },
        label = { Text("장소 이름이나 주소") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { if (canSearch) viewModel.searchDestination(keyword) }),
        modifier = Modifier.fillMaxWidth(),
    )
    PrimaryButton("내비에서 검색", { viewModel.searchDestination(keyword) }, enabled = canSearch)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PurposeSection(state: MeetRouteUiState, group: GroupState, viewModel: MeetRouteViewModel) {
    Section("만남 목적") {
        if (state.isLeader) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MeetingPurpose.entries.forEach { purpose ->
                    FilterChip(
                        selected = group.meetingPurpose == purpose,
                        onClick = {
                            val offset = if (purpose == MeetingPurpose.MEAL) MEAL_OFFSETS_MINUTES[1] else null
                            viewModel.setPurpose(purpose, offset)
                        },
                        label = { Text(purpose.label) },
                        enabled = !state.busy,
                    )
                }
            }
            if (group.meetingPurpose == MeetingPurpose.MEAL) {
                Text("식사 시각", color = TextMuted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MEAL_OFFSETS_MINUTES.forEach { minutes ->
                        FilterChip(
                            selected = false,
                            onClick = { viewModel.setPurpose(MeetingPurpose.MEAL, minutes) },
                            label = { Text("${minutes / 60}시간 뒤") },
                            enabled = !state.busy,
                        )
                    }
                }
            }
        } else {
            Text(group.meetingPurpose.label, style = MaterialTheme.typography.titleMedium)
        }
    }
}
