package com.meetroute.smoke

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "MeetRouteSmoke"
private const val PERMISSION_REQUEST = 1

// 좌표 출처: data/raw/한국도로공사_휴게소정보.csv (덕평휴게소, 영동선, 양방향)
private const val WAYPOINT_LAT = 37.241456
private const val WAYPOINT_LON = 127.390189
private const val WAYPOINT_NAME = "덕평휴게소"

private const val SECOND_WAYPOINT_LAT = 37.335469
private const val SECOND_WAYPOINT_LON = 127.8582201
private const val SECOND_WAYPOINT_NAME = "문막(강릉)"

private const val DESTINATION_LAT = 37.7640
private const val DESTINATION_LON = 128.8996
private const val DESTINATION_NAME = "강릉역"
private const val SEARCH_KEYWORD = "판교역"

/**
 * NaviHelper 경유지 등록이 에뮬레이터에서 실제로 되는지 확인하는 화면.
 *
 * 문서만으로는 addWaypoint가 경로 안내 중에만 동작하는지 알 수 없으므로, addWaypoint 단독 호출과
 * requestRouteWithWaypoints를 각각 시험한다. 호출 성공이 아니라 콜백 수신을 성공으로 본다.
 */
class SmokeActivity : Activity() {

    private lateinit var naviHelper: NaviHelper
    private var fusedLocation: FusedLocation? = null
    private var naviConnected = false

    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private val naviListener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = log("EVENT onRouteStarted $routeStartInfo")
        override fun onRoutingCompleted() = log("EVENT onRoutingCompleted")
        override fun onRouteEnded() = log("EVENT onRouteEnded")
        override fun onReRouteEnded() = log("EVENT onReRouteEnded")
        override fun onRouteCancelled() = log("EVENT onRouteCancelled")
        override fun onDestinationChanged() = log("EVENT onDestinationChanged")
        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) =
            log("EVENT onWaypointChanged $waypointChangedInfo")
        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) =
            log("EVENT onWayPointArrived $waypointArrivedInfo")
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) =
            log("EVENT onDestinationArrived $destinationArrivedInfo")
        override fun onError(errorCode: NaviErrorCode) = log("ERROR onError $errorCode")
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) =
            log("RESULT onCurrentLocationInfo $currentLocationInfo")
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) =
            log("RESULT onRouteStateInfo $routeStateInfo")
        override fun onDestinationInfo(destinationInfo: DestinationInfo) =
            log("RESULT onDestinationInfo $destinationInfo")
        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) =
            log("RESULT onWayPointInfo $waypointInfoList")
        override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) =
            log("RESULT onRecentDestinationInfo $recentDestinationInfoList")
        override fun onDrivingInfo(drivingInfo: DrivingInfo) = log("EVENT onDrivingInfo $drivingInfo")
        override fun onNavigationStatus(status: NavigationStatus) =
            log("EVENT onNavigationStatus $status")
    }

    private val fusedListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) = log("RESULT fused onChanged $location")
        override fun onError(exception: Exception) = log("ERROR fused onError $exception")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        naviHelper = NaviHelper(applicationContext)
        setContentView(buildContent())
        log("SDK 대상: NaviHelper 3.0.5 / FusedLocation 1.1.3")
        requestPleosPermissions()
    }

    private fun buildContent(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun add(label: String, action: () -> Unit) {
            buttons.addView(Button(this).apply {
                text = label
                textSize = 18f
                setOnClickListener {
                    log("CALL $label")
                    runCatching(action).onFailure { log("ERROR $label 예외: $it") }
                }
            })
        }
        add("1. NaviHelper 초기화") { connectNavi() }
        add("2. addWaypoint 단독") { naviHelper.addWaypoint(waypoint()) }
        add("3. 목적지 안내 시작") { naviHelper.requestRoute(destination()) }
        add("4. 안내 중 addWaypoint") { naviHelper.addWaypoint(waypoint()) }
        add("5. 목적지+경유지 동시 요청") {
            naviHelper.requestRouteWithWaypoints(
                RouteWithWaypointsRequest(destination = destination(), waypoints = listOf(waypoint()))
            )
        }
        add("6. 경유지 정보(남은 시간)") { naviHelper.getWaypointInfo() }
        add("7. 목적지 정보(남은 시간)") { naviHelper.getDestinationInfo() }
        add("8. 경로 상태") { naviHelper.getRouteStateInfo() }
        add("9. 현재 위치(Navi)") { naviHelper.getCurrentLocationInfo() }
        add("10. 경유지 전체 삭제") { naviHelper.removeWaypoint(WaypointIndex.ALL) }
        add("11. 안내 취소") { naviHelper.cancelRoute() }
        add("12. FusedLocation 시험") { startFusedLocation() }
        add("13. 마지막 위치만 조회") { fusedLocation?.getLastKnownLocation(fusedListener) }
        add("14. 두 번째 경유지 추가") { naviHelper.addWaypoint(secondWaypoint()) }
        add("15. 첫 번째 경유지 삭제") { naviHelper.removeWaypoint(WaypointIndex.FIRST) }
        add("16. 내비 장소 검색(키워드)") {
            naviHelper.requestNavigationScreen(NavigationScreenRequest.SearchPlace(SEARCH_KEYWORD))
        }
        add("17. 내비 장소 검색(빈 값)") {
            naviHelper.requestNavigationScreen(NavigationScreenRequest.SearchPlace(""))
        }
        add("18. 최근 목적지") { naviHelper.getRecentDestinationInfo() }

        logView = TextView(this).apply {
            setTextColor(Color.GREEN)
            typeface = Typeface.MONOSPACE
            textSize = 14f
        }
        logScroll = ScrollView(this).apply { addView(logView) }

        root.addView(
            ScrollView(this).apply { addView(buttons) },
            LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)
        )
        root.addView(logScroll, LinearLayout.LayoutParams(0, MATCH_PARENT, 2f))
        root.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        return root
    }

    private fun connectNavi() {
        naviHelper.initialize(
            onConnected = {
                naviConnected = true
                naviHelper.addListener(naviListener)
                log("OK NaviHelper 연결됨")
            },
            onFailed = { throwable -> log("ERROR NaviHelper 연결 실패: $throwable") },
        )
    }

    private fun waypoint() = RequestWaypointInfo(
        latitude = WAYPOINT_LAT,
        longitude = WAYPOINT_LON,
        poiName = WAYPOINT_NAME,
        waypointIndex = WaypointIndex.FIRST,
        poiId = "",
        address = "",
        poiSubId = "0",
    )

    // 첫 경유지를 지웠을 때 두 번째의 번호가 밀리는지 볼 때 쓴다.
    private fun secondWaypoint() = RequestWaypointInfo(
        latitude = SECOND_WAYPOINT_LAT,
        longitude = SECOND_WAYPOINT_LON,
        poiName = SECOND_WAYPOINT_NAME,
        waypointIndex = WaypointIndex.SECOND,
        poiId = "",
        address = "",
        poiSubId = "0",
    )

    private fun destination() = RouteInfo(
        latitude = DESTINATION_LAT,
        longitude = DESTINATION_LON,
        displayLatitude = null,
        displayLongitude = null,
        poiName = DESTINATION_NAME,
        poiId = "",
        address = "",
        poiSubId = "0",
    )

    private fun startFusedLocation() {
        val sdk = fusedLocation ?: FusedLocation(this).also { fusedLocation = it }
        val supported = sdk.isSupported()
        log("RESULT FusedLocation.isSupported = $supported")
        if (!supported) return
        sdk.initialize()
        sdk.getLastKnownLocation(fusedListener)
        sdk.registerFusedLocationCallback(fusedListener)
    }

    private fun requestPleosPermissions() {
        val missing = listOf(
            "pleos.car.permission.NAVI_ROUTE",
            "pleos.car.permission.NAVI_ROUTE_SEARCH",
            "pleos.car.permission.NAVI_CUSTOM_MAP",
            "pleos.car.permission.FUSED_LOCATION",
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissions.forEachIndexed { index, permission ->
            val granted = grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED
            log("권한 $permission = ${if (granted) "허용" else "거부"}")
        }
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        runOnUiThread {
            val time = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
            logView.append("$time $message\n")
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onDestroy() {
        if (naviConnected) {
            naviHelper.removeListener(naviListener)
            naviHelper.release()
        }
        fusedLocation?.let {
            runCatching {
                it.unregisterFusedLocationCallback(fusedListener)
                it.release()
            }
        }
        super.onDestroy()
    }
}
