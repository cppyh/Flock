package com.meetroute.app.data

import android.content.Context
import androidx.core.content.edit
import com.meetroute.app.domain.StoredSession
import com.meetroute.app.navigation.AppliedPointStore
import com.meetroute.app.navigation.NavigationTarget

private const val PREFERENCES = "meetroute"
private const val KEY_SERVER_URL = "server_url"
private const val KEY_GROUP_ID = "group_id"
private const val KEY_VEHICLE_ID = "vehicle_id"
private const val KEY_ACCESS_TOKEN = "access_token"
private const val KEY_APPLIED_NAME = "applied_name"
private const val KEY_APPLIED_LAT = "applied_lat"
private const val KEY_APPLIED_LON = "applied_lon"
private const val KEY_APPLIED_PLACE_ID = "applied_place_id"

/** 앱을 다시 켰을 때 진행 중인 여행으로 돌아가기 위한 저장소. 차량 위치는 저장하지 않는다. */
class SessionStore(context: Context, private val defaultServerUrl: String) : AppliedPointStore {

    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = preferences.getString(KEY_SERVER_URL, null) ?: defaultServerUrl
        set(value) = preferences.edit { putString(KEY_SERVER_URL, value) }

    /** 내비에 넣은 합류 지점. 공공 휴게소 좌표이며 재시작 뒤에도 이전 지점을 찾아 지우는 데 쓴다. */
    override var meetingPoint: NavigationTarget?
        get() {
            val name = preferences.getString(KEY_APPLIED_NAME, null) ?: return null
            val lat = preferences.getString(KEY_APPLIED_LAT, null)?.toDoubleOrNull() ?: return null
            val lon = preferences.getString(KEY_APPLIED_LON, null)?.toDoubleOrNull() ?: return null
            return NavigationTarget(name, lat, lon, placeId = preferences.getString(KEY_APPLIED_PLACE_ID, null))
        }
        set(value) = preferences.edit {
            if (value == null) {
                removeAppliedPoint()
            } else {
                putString(KEY_APPLIED_NAME, value.name)
                putString(KEY_APPLIED_LAT, value.lat.toString())
                putString(KEY_APPLIED_LON, value.lon.toString())
                putString(KEY_APPLIED_PLACE_ID, value.placeId)
            }
        }

    fun load(): StoredSession? {
        val groupId = preferences.getString(KEY_GROUP_ID, null) ?: return null
        val vehicleId = preferences.getString(KEY_VEHICLE_ID, null) ?: return null
        val token = preferences.getString(KEY_ACCESS_TOKEN, null) ?: return null
        return StoredSession(serverUrl, groupId, vehicleId, token)
    }

    fun save(groupId: String, vehicleId: String, accessToken: String) = preferences.edit {
        putString(KEY_GROUP_ID, groupId)
        putString(KEY_VEHICLE_ID, vehicleId)
        putString(KEY_ACCESS_TOKEN, accessToken)
    }

    fun clear() = preferences.edit {
        remove(KEY_GROUP_ID)
        remove(KEY_VEHICLE_ID)
        remove(KEY_ACCESS_TOKEN)
        removeAppliedPoint()
    }

    private fun android.content.SharedPreferences.Editor.removeAppliedPoint() {
        remove(KEY_APPLIED_NAME)
        remove(KEY_APPLIED_LAT)
        remove(KEY_APPLIED_LON)
        remove(KEY_APPLIED_PLACE_ID)
    }
}
