package com.meetroute.app.navigation

enum class WaypointSlot { FIRST, SECOND }

data class WaypointSnapshot(
    val name: String,
    val remainSeconds: Int,
    val remainMeters: Int,
    val lat: Double? = null,
    val lon: Double? = null,
    val poiId: String = "",
    val poiSubId: String = "",
    val address: String = "",
)

data class DestinationSnapshot(val target: NavigationTarget, val remainSeconds: Int, val remainMeters: Int)

/** SDK 콜백을 앱의 값으로 옮긴 것. 호출 스레드는 보장되지 않는다. */
interface NaviHelperEvents {
    /** 경로 안내 중이면 true다. SDK의 isRouting은 안내 중에도 false여서 쓰지 않는다. */
    fun onRouteState(guiding: Boolean)
    fun onWaypoints(waypoints: List<WaypointSnapshot>)
    fun onDestination(destination: DestinationSnapshot)
    fun onCurrentLocation(position: VehiclePosition)
    fun onWaypointChanged()
    fun onDestinationChanged()
    fun onRouteStarted()
    fun onRoutingCompleted()
    fun onReRouteEnded()
    fun onRouteCancelled()
    fun onRouteEnded()

    /** 내비가 경로 주행과 자유 주행 사이를 오갈 때 온다. */
    fun onGuidanceChanged(guiding: Boolean)
    fun onWaypointArrived(name: String, slot: WaypointSlot?)
    fun onDestinationArrived(name: String)
    fun onError(code: String)
}

/** NaviHelper SDK 호출 경계. 게이트웨이의 실패 처리를 SDK 없이 시험할 수 있게 한다. */
interface NaviHelperPort {
    fun initialize(events: NaviHelperEvents, onConnected: () -> Unit, onFailed: (Throwable) -> Unit)
    fun requestRouteState()
    fun requestWaypoints()
    fun requestDestination()
    fun requestCurrentLocation()
    fun addWaypoint(target: NavigationTarget, slot: WaypointSlot)
    fun removeWaypoint(slot: WaypointSlot)
    fun requestRoute(destination: NavigationTarget)
    fun requestRouteWithWaypoints(destination: NavigationTarget, waypoints: List<NavigationTarget>)
    fun requestReRoute()
    fun openNavigationScreen()
    fun openPlaceSearch(keyword: String)
    fun release()
}
