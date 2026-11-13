package com.meetroute.app.navigation

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
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
import android.content.Context
import android.util.Log

private const val DEFAULT_POI_SUB_ID = "0"

// 콜백 순서를 Pleos 환경에서 확인하려고 남긴다. 차량 좌표는 기록하지 않는다.
private const val TAG = "MeetRouteNavi"

/** 실제 Pleos NaviHelper SDK 호출. 시그니처는 3.0.5 AAR 기준이다(docs/sdk-integration.md). */
class SdkNaviHelperPort(context: Context) : NaviHelperPort {

    private val helper = NaviHelper(context.applicationContext)
    private var listener: NaviHelperEventListener? = null

    override fun initialize(events: NaviHelperEvents, onConnected: () -> Unit, onFailed: (Throwable) -> Unit) {
        helper.initialize(
            onConnected = {
                Log.i(TAG, "연결됨")
                val registered = events.asSdkListener()
                listener = registered
                helper.addListener(registered)
                onConnected()
            },
            onFailed = { error ->
                Log.w(TAG, "연결 실패: ${error.javaClass.simpleName}")
                onFailed(error)
            },
        )
    }

    override fun requestRouteState() = logged("getRouteStateInfo") { helper.getRouteStateInfo() }

    override fun requestWaypoints() = logged("getWaypointInfo") { helper.getWaypointInfo() }

    override fun requestDestination() = logged("getDestinationInfo") { helper.getDestinationInfo() }

    override fun requestCurrentLocation() = logged("getCurrentLocationInfo") { helper.getCurrentLocationInfo() }

    override fun addWaypoint(target: NavigationTarget, slot: WaypointSlot) = logged("addWaypoint") {
        Log.i(TAG, "요청 addWaypoint ${target.name} $slot")
        helper.addWaypoint(target.toWaypoint(slot))
    }

    override fun removeWaypoint(slot: WaypointSlot) = logged("removeWaypoint") {
        Log.i(TAG, "요청 removeWaypoint $slot")
        helper.removeWaypoint(slot.toIndex())
    }

    override fun requestRoute(destination: NavigationTarget) = logged("requestRoute") {
        Log.i(TAG, "요청 requestRoute ${destination.name}")
        helper.requestRoute(destination.toRouteInfo())
    }

    override fun requestRouteWithWaypoints(destination: NavigationTarget, waypoints: List<NavigationTarget>) =
        logged("requestRouteWithWaypoints") {
            Log.i(TAG, "요청 requestRouteWithWaypoints ${destination.name} ${waypoints.map { it.name }}")
            helper.requestRouteWithWaypoints(
                RouteWithWaypointsRequest(
                    destination = destination.toRouteInfo(),
                    waypoints = waypoints.mapIndexed { index, target -> target.toWaypoint(WaypointSlot.entries[index]) },
                )
            )
        }

    override fun requestReRoute() = logged("requestReRoute") {
        Log.i(TAG, "요청 requestReRoute")
        helper.requestReRoute()
    }

    /** SDK는 권한이 없으면 예외를 던진다. 게이트웨이는 일반 실패로만 보여 주므로 원인을 여기 남긴다. */
    private inline fun logged(call: String, request: () -> Unit) {
        try {
            request()
        } catch (error: RuntimeException) {
            Log.w(TAG, "$call 실패: ${error.javaClass.simpleName} ${error.message}")
            throw error
        }
    }

    override fun openNavigationScreen() = helper.requestNavigationScreen(NavigationScreenRequest.Foreground)

    override fun openPlaceSearch(keyword: String) = logged("SearchPlace") {
        helper.requestNavigationScreen(NavigationScreenRequest.SearchPlace(keyword))
    }

    override fun release() {
        listener?.let(helper::removeListener)
        listener = null
        helper.release()
    }

    private fun NavigationTarget.toRouteInfo() = RouteInfo(
        latitude = lat,
        longitude = lon,
        displayLatitude = null,
        displayLongitude = null,
        poiName = name,
        poiId = poiId,
        address = address,
        poiSubId = poiSubId.ifEmpty { DEFAULT_POI_SUB_ID },
    )

    private fun NavigationTarget.toWaypoint(slot: WaypointSlot) = RequestWaypointInfo(
        latitude = lat,
        longitude = lon,
        poiName = name,
        waypointIndex = slot.toIndex(),
        poiId = poiId,
        address = address,
        poiSubId = poiSubId.ifEmpty { DEFAULT_POI_SUB_ID },
    )

    private fun WaypointSlot.toIndex() = when (this) {
        WaypointSlot.FIRST -> WaypointIndex.FIRST
        WaypointSlot.SECOND -> WaypointIndex.SECOND
    }

    private fun WaypointIndex.toSlot(): WaypointSlot? = when (this) {
        WaypointIndex.FIRST -> WaypointSlot.FIRST
        WaypointIndex.SECOND -> WaypointSlot.SECOND
        else -> null
    }

    private fun WaypointInfo.toSnapshot() = WaypointSnapshot(
        name = name,
        remainSeconds = remainTime,
        remainMeters = remainDistance,
        lat = latitude,
        lon = longitude,
        poiId = poiId.orEmpty(),
        poiSubId = poiSubId.orEmpty(),
        address = address.orEmpty(),
    )

    private fun NaviHelperEvents.asSdkListener() = object : NaviHelperEventListener {
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            val mode = routeStateInfo.naviMode
            Log.d(TAG, "상태 naviMode=$mode isRouting=${routeStateInfo.isRouting} terms=${routeStateInfo.termsAgreement}")
            onRouteState(mode == NaviMode.ROUTE_GUIDING || mode == NaviMode.SIMULATION_GUIDING)
        }

        override fun onNavigationStatus(status: NavigationStatus) {
            Log.i(TAG, "이벤트 onNavigationStatus $status")
            when (status) {
                NavigationStatus.ROUTE_DRIVE -> onGuidanceChanged(true)
                NavigationStatus.FREE_DRIVE -> onGuidanceChanged(false)
                else -> Unit
            }
        }

        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) {
            Log.d(TAG, "경유지 ${waypointInfoList.map { "${it.name} ${it.remainTime}/${it.remainDistance}" }}")
            onWaypoints(waypointInfoList.map { it.toSnapshot() })
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            Log.d(TAG, "목적지 ${destinationInfo.name} ${destinationInfo.remainTime}/${destinationInfo.remainDistance}")
            onDestination(
                DestinationSnapshot(
                    NavigationTarget(
                        destinationInfo.name.ifBlank { destinationInfo.address.orEmpty() },
                        destinationInfo.latitude,
                        destinationInfo.longitude,
                    ),
                    destinationInfo.remainTime,
                    destinationInfo.remainDistance,
                )
            )
        }

        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) = onCurrentLocation(
            VehiclePosition(
                currentLocationInfo.latitude,
                currentLocationInfo.longitude,
                currentLocationInfo.heading,
            )
        )

        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) {
            Log.i(TAG, "이벤트 onWaypointChanged ${waypointChangedInfo.index} ${waypointChangedInfo.cmdType}")
            this@asSdkListener.onWaypointChanged()
        }

        override fun onDestinationChanged() {
            Log.i(TAG, "이벤트 onDestinationChanged")
            this@asSdkListener.onDestinationChanged()
        }

        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            Log.i(TAG, "이벤트 onRouteStarted ${routeStartInfo.name} ${routeStartInfo.duration}/${routeStartInfo.distance}")
            this@asSdkListener.onRouteStarted()
        }

        override fun onRoutingCompleted() {
            Log.i(TAG, "이벤트 onRoutingCompleted")
            this@asSdkListener.onRoutingCompleted()
        }

        override fun onReRouteEnded() {
            Log.i(TAG, "이벤트 onReRouteEnded")
            this@asSdkListener.onReRouteEnded()
        }

        override fun onRouteCancelled() {
            Log.i(TAG, "이벤트 onRouteCancelled")
            this@asSdkListener.onRouteCancelled()
        }

        override fun onRouteEnded() {
            Log.i(TAG, "이벤트 onRouteEnded")
            this@asSdkListener.onRouteEnded()
        }

        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) {
            Log.i(TAG, "이벤트 onWayPointArrived ${waypointArrivedInfo.index} ${waypointArrivedInfo.name}")
            onWaypointArrived(waypointArrivedInfo.name.orEmpty(), waypointArrivedInfo.index.toSlot())
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            Log.i(TAG, "이벤트 onDestinationArrived ${destinationArrivedInfo.name}")
            this@asSdkListener.onDestinationArrived(destinationArrivedInfo.name.orEmpty())
        }

        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "오류 $errorCode")
            this@asSdkListener.onError(errorCode.name)
        }
    }
}
