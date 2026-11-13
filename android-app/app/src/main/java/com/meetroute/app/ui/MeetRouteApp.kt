package com.meetroute.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meetroute.app.ui.screens.CompletedScreen
import com.meetroute.app.ui.screens.CreateGroupScreen
import com.meetroute.app.ui.screens.DrivingScreen
import com.meetroute.app.ui.screens.HomeScreen
import com.meetroute.app.ui.screens.JoinGroupScreen
import com.meetroute.app.ui.screens.LobbyScreen
import com.meetroute.app.ui.screens.RecommendationScreen

@Composable
fun MeetRouteApp(viewModel: MeetRouteViewModel, onRequestSharing: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Surface가 글자 기본색을 배경에 맞춘다. 없으면 카드 밖 글자가 어두운 배경에 검정으로 나온다.
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (state.screen) {
            Screen.HOME -> HomeScreen(state, viewModel)
            Screen.CREATE -> CreateGroupScreen(state, viewModel)
            Screen.JOIN -> JoinGroupScreen(state, viewModel)
            Screen.LOBBY -> LobbyScreen(state, viewModel, onRequestSharing)
            Screen.RECOMMENDATIONS -> RecommendationScreen(state, viewModel)
            Screen.DRIVING -> DrivingScreen(state, viewModel, onRequestSharing)
            Screen.COMPLETED -> CompletedScreen(state, viewModel)
        }
    }
}
