package com.meetroute.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meetroute.app.domain.DriveTheme
import com.meetroute.app.domain.GroupState
import com.meetroute.app.domain.freeDriveSummary
import com.meetroute.app.ui.MeetRouteUiState
import com.meetroute.app.ui.MeetRouteViewModel
import com.meetroute.app.ui.components.Banner
import com.meetroute.app.ui.components.PrimaryButton
import com.meetroute.app.ui.components.SecondaryButton
import com.meetroute.app.ui.theme.Accent
import com.meetroute.app.ui.theme.TextMuted

private val DRIVE_HOURS = 1..5
private const val DEFAULT_DRIVE_HOURS = 2
private const val TOLERANCE_MINUTES = 15

/** 그룹장이 목적지 없는 드라이브를 설정한다. 시간(왕복)과 테마를 고르면 서버가 명소를 고른다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FreeDriveSetup(state: MeetRouteUiState, group: GroupState, viewModel: MeetRouteViewModel) {
    val current = group.freeDrive
    var hours by rememberSaveable { mutableIntStateOf(current?.hours ?: DEFAULT_DRIVE_HOURS) }
    var theme by rememberSaveable { mutableStateOf(current?.theme ?: DriveTheme.RANDOM) }
    var hidden by rememberSaveable { mutableStateOf(current?.isPrivate ?: false) }
    // 코스가 정해지면 설정을 접어 합류 추천 버튼이 화면 밖으로 밀리지 않게 한다.
    var editing by rememberSaveable { mutableStateOf(current == null) }
    LaunchedEffect(current?.planId) { editing = current == null }

    if (current != null) {
        FreeDriveSummary(group)
        if (!editing) {
            SecondaryButton(
                "다른 곳 추천",
                { viewModel.startFreeDrive(current.hours, current.theme, current.isPrivate, reroll = true) },
                enabled = !state.busy,
            )
            SecondaryButton("조건 바꾸기", { editing = true }, enabled = !state.busy)
            return
        }
    }

    Text("드라이브 시간 (왕복)", color = TextMuted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DRIVE_HOURS.forEach { value ->
            FilterChip(
                selected = hours == value,
                onClick = { hours = value },
                label = { Text("${value}시간") },
                enabled = !state.busy,
            )
        }
    }
    Text("정한 시간 ±${TOLERANCE_MINUTES}분 안의 코스를 무작위로 고릅니다.", color = TextMuted)
    Text("테마", color = TextMuted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DriveTheme.entries.forEach { option ->
            FilterChip(
                selected = theme == option,
                onClick = { theme = option },
                label = { Text(option.label) },
                enabled = !state.busy,
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("목적지 비공개")
            Text("그룹원은 모두 합류할 때 목적지를 알게 됩니다.", color = TextMuted)
        }
        Switch(checked = hidden, onCheckedChange = { hidden = it }, enabled = !state.busy)
    }
    PrimaryButton(
        if (current == null) "코스 추천받기" else "이 조건으로 다시 추천",
        { viewModel.startFreeDrive(hours, theme, hidden) },
        enabled = !state.busy,
    )
    if (current != null) SecondaryButton("접기", { editing = false })
}

/** 고른 코스. 비공개면 그룹원에게는 테마와 시간만 보인다. */
@Composable
fun FreeDriveSummary(group: GroupState) {
    val drive = group.freeDrive ?: return
    val destination = group.destination
    Text(
        destination?.name ?: "비공개 목적지",
        style = MaterialTheme.typography.headlineMedium,
        color = Accent,
    )
    Text(freeDriveSummary(drive), style = MaterialTheme.typography.titleMedium)
    val detail = listOfNotNull(drive.region, drive.description).joinToString(" · ")
    if (detail.isNotEmpty()) Text(detail, color = TextMuted)
    when {
        group.destinationHidden -> Banner("목적지는 모두 합류하면 공개됩니다. 합류 지점까지는 내비가 안내합니다.", Accent)
        drive.isPrivate && !drive.revealed -> Text("그룹원에게는 목적지가 가려져 있습니다.", color = TextMuted)
    }
    drive.notice?.let { Banner(it) }
}
