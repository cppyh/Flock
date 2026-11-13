package com.meetroute.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.meetroute.app.ui.MeetRouteUiState
import com.meetroute.app.ui.MeetRouteViewModel
import com.meetroute.app.ui.Screen
import com.meetroute.app.ui.components.AdaptivePanes
import com.meetroute.app.ui.components.Banner
import com.meetroute.app.ui.components.PrimaryButton
import com.meetroute.app.ui.components.SecondaryButton
import com.meetroute.app.ui.components.Section
import com.meetroute.app.ui.theme.Accent
import com.meetroute.app.ui.theme.TextMuted

private const val INVITE_CODE_LENGTH = 6

@Composable
fun HomeScreen(state: MeetRouteUiState, viewModel: MeetRouteViewModel) {
    AdaptivePanes(
        modifier = Modifier.padding(32.dp),
        first = {
            Text("Flock", style = MaterialTheme.typography.headlineLarge, color = Accent)
            Text("각자 길을 찾는 내비게이션에서,\n함께 이동을 조율하는 내비게이션으로.", style = MaterialTheme.typography.titleMedium)
            Text("여러 차량의 합류 지점을 찾아 드립니다.", color = TextMuted)
            state.message?.let { Banner(it) }
        },
        second = {
            if (state.hasStoredSession) {
                PrimaryButton("진행 중인 여행 이어가기", viewModel::resumeSession, enabled = !state.busy)
            }
            PrimaryButton("그룹 만들기", { viewModel.open(Screen.CREATE) }, enabled = !state.busy)
            SecondaryButton("초대 코드로 참여", { viewModel.open(Screen.JOIN) }, enabled = !state.busy)
            SecondaryButton("오프라인 시연 모드", viewModel::startDemo, enabled = !state.busy)
            ServerSettings(state, viewModel)
        },
    )
}

@Composable
private fun ServerSettings(state: MeetRouteUiState, viewModel: MeetRouteViewModel) {
    var url by remember(state.serverUrl) { mutableStateOf(state.serverUrl) }
    Section("서버 주소") {
        OutlinedTextField(
            value = url,
            onValueChange = {
                url = it
                viewModel.updateServerUrl(it)
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun CreateGroupScreen(state: MeetRouteUiState, viewModel: MeetRouteViewModel) {
    var name by rememberSaveable { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }
    FormScreen(title = "그룹 만들기", state = state, onBack = { viewModel.open(Screen.HOME) }) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            label = { Text("그룹 이름") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = nickname,
            onValueChange = { nickname = it.take(20) },
            label = { Text("차량 별칭") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton(
            "만들기",
            { viewModel.createGroup(name.trim(), nickname.trim()) },
            enabled = name.isNotBlank() && nickname.isNotBlank() && !state.busy,
        )
    }
}

@Composable
fun JoinGroupScreen(state: MeetRouteUiState, viewModel: MeetRouteViewModel) {
    var code by rememberSaveable { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }
    FormScreen(title = "초대 코드로 참여", state = state, onBack = { viewModel.open(Screen.HOME) }) {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.uppercase().take(INVITE_CODE_LENGTH) },
            label = { Text("초대 코드 6자리") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = nickname,
            onValueChange = { nickname = it.take(20) },
            label = { Text("차량 별칭") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton(
            "참여하기",
            { viewModel.joinGroup(code, nickname.trim()) },
            enabled = code.length == INVITE_CODE_LENGTH && nickname.isNotBlank() && !state.busy,
        )
    }
}

@Composable
private fun FormScreen(
    title: String,
    state: MeetRouteUiState,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (state.isDriving) {
            Banner("주행 중에는 그룹 설정을 할 수 없습니다. 안전한 곳에 정차해 주세요.")
        } else {
            state.message?.let { Banner(it) }
            content()
        }
        SecondaryButton("뒤로", onBack)
    }
}
