package com.meetroute.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.meetroute.app.location.CAR_ENERGY_PERMISSION
import com.meetroute.app.location.FUSED_LOCATION_PERMISSION
import com.meetroute.app.ui.MeetRouteApp
import com.meetroute.app.ui.MeetRouteViewModel
import com.meetroute.app.ui.theme.MeetRouteTheme

private val LOCATION_PERMISSIONS = arrayOf(
    FUSED_LOCATION_PERMISSION,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

private val NAVIGATION_PERMISSIONS = arrayOf(
    "pleos.car.permission.NAVI_ROUTE",
    "pleos.car.permission.NAVI_ROUTE_SEARCH",
    "pleos.car.permission.NAVI_CUSTOM_MAP",
)

class MainActivity : ComponentActivity() {

    private val viewModel: MeetRouteViewModel by viewModels {
        val container = (application as MeetRouteApplication).container
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MeetRouteViewModel(container) as T
        }
    }

    private val sharingPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (LOCATION_PERMISSIONS.any { results[it] == true }) {
                viewModel.setLocationSharing(true)
            } else {
                viewModel.reportMessage("위치 권한이 없어 공유를 시작할 수 없습니다.")
            }
        }

    // 권한 창이 닫힌 뒤 연결해야 권한 때문에 첫 연결이 실패하지 않는다.
    private val navigationPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            viewModel.connectNavigation()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            navigationPermissions.launch(NAVIGATION_PERMISSIONS)
        } else {
            viewModel.connectNavigation()
        }
        setContent {
            MeetRouteTheme {
                MeetRouteApp(viewModel, onRequestSharing = { sharingPermissions.launch(sharingRequest()) })
            }
        }
    }

    // 공유 중 알림이 보이려면 Android 13부터 알림 권한이 필요하다. 거부해도 공유는 할 수 있다.
    // 배터리 권한도 선택이다. 허용하면 전기차 잔량을 함께 보내 충전을 고려한 합류 지점을 추천받는다.
    private fun sharingRequest(): Array<String> {
        val optional = buildList {
            add(CAR_ENERGY_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return LOCATION_PERMISSIONS + optional
    }
}
