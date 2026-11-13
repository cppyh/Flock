package com.meetroute.app.di

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.meetroute.app.BuildConfig
import com.meetroute.app.data.DEMO_ASSET_PATH
import com.meetroute.app.data.FixtureMeetPointRepository
import com.meetroute.app.data.RoadRoutingMeetPointRepository
import com.meetroute.app.data.SessionStore
import com.meetroute.app.domain.MeetPointRepository
import com.meetroute.app.location.AndroidLocationSource
import com.meetroute.app.location.CarUxRestrictionSource
import com.meetroute.app.location.DrivingRestrictionSource
import com.meetroute.app.location.LocationSharingController
import com.meetroute.app.location.LocationSharingService
import com.meetroute.app.location.LocationSourceSelector
import com.meetroute.app.location.PleosEvBatterySource
import com.meetroute.app.location.PleosFusedLocationSource
import com.meetroute.app.location.PleosNaviLocationSource
import com.meetroute.app.navigation.FallbackNavigationGateway
import com.meetroute.app.navigation.NavigationGateway
import com.meetroute.app.navigation.PleosNaviHelperGateway
import com.meetroute.app.navigation.RecordingNavigationGateway
import com.meetroute.app.navigation.SdkNaviHelperPort
import com.meetroute.app.voice.AndroidVoiceOutput
import com.meetroute.app.voice.VoiceOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private const val FOREGROUND_ONLY_NOTICE = "앱이 화면에 있을 때만 위치를 공유합니다. 내비로 전환해도 공유하려면 Android 위치 권한을 허용해 주세요."

/** 앱 전역 객체 구성. 어떤 구현을 쓸지는 이 파일에서만 결정한다. */
class AppContainer(private val context: Context) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    val sessionStore = SessionStore(context, BuildConfig.DEFAULT_SERVER_URL)

    val navigation: NavigationGateway = FallbackNavigationGateway(
        primary = PleosNaviHelperGateway(portFactory = { SdkNaviHelperPort(context) }, appliedPoints = sessionStore),
        fallback = RecordingNavigationGateway(),
    )

    private val locationSelector = LocationSourceSelector(
        listOf(
            PleosFusedLocationSource(context),
            PleosNaviLocationSource(navigation),
            AndroidLocationSource(context),
        )
    )

    val locationSharing = LocationSharingController(
        locationSelector,
        navigation,
        scope,
        battery = PleosEvBatterySource(context, scope),
    )

    val drivingRestriction: DrivingRestrictionSource = CarUxRestrictionSource(context)

    val voice: VoiceOutput by lazy { AndroidVoiceOutput(context) }

    var repository: MeetPointRepository? = null

    private val _sharingNotice = MutableStateFlow<String?>(null)

    /** 위치 공유가 제한적으로 동작할 때 화면에 보여 줄 안내. */
    val sharingNotice: StateFlow<String?> = _sharingNotice.asStateFlow()

    fun serverRepository(): MeetPointRepository =
        RoadRoutingMeetPointRepository(sessionStore.serverUrl, http, scope)

    fun demoRepository(): MeetPointRepository {
        val scenario = context.assets.open(DEMO_ASSET_PATH).bufferedReader().use { it.readText() }
        return FixtureMeetPointRepository(scenario, scope)
    }

    /** 위치 타입 포그라운드 서비스는 Android 위치 권한이 있어야 한다. Pleos 권한만으로는 시작할 수 없다. */
    fun startSharingService() {
        val started = hasAndroidLocationPermission() && LocationSharingService.start(context)
        _sharingNotice.value = if (started) null else FOREGROUND_ONLY_NOTICE
    }

    fun stopSharingService() {
        _sharingNotice.value = null
        LocationSharingService.stop(context)
    }

    fun onForegroundServiceFailed() {
        _sharingNotice.value = FOREGROUND_ONLY_NOTICE
    }

    private fun hasAndroidLocationPermission(): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** 앱을 완전히 닫으면 공유를 끈다. 실패해도 서버가 20초 뒤 STALE로 표시한다. */
    fun onTaskRemoved() {
        locationSharing.stop()
        val current = repository ?: return
        scope.launch { runCatching { current.setLocationSharing(false) } }
    }
}
