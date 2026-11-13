package com.meetroute.app.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.meetroute.app.MeetRouteApplication
import com.meetroute.app.R

private const val CHANNEL_ID = "location_sharing"
private const val NOTIFICATION_ID = 1
private const val TAG = "MeetRoute"

/**
 * 내비게이션 화면으로 넘어가도 위치 공유가 끊기지 않도록 프로세스를 유지하고 공유 상태를 항상 표시한다.
 * 앱을 완전히 종료하면 서버의 공유도 함께 끈다(design.md §14.2).
 *
 * startForegroundService()로 시작하면 전환에 실패하는 순간 시간 초과로 앱이 죽는다. 공유는 앱이 화면에
 * 있을 때 시작하므로 일반 시작 뒤 포그라운드로 전환해, 실패해도 서비스만 정리하고 앱은 계속 돌게 한다.
 */
class LocationSharingService : Service() {

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.sharing_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (error: SecurityException) {
            giveUpForeground(error)
        } catch (error: IllegalStateException) {
            // 앱이 그사이 백그라운드로 내려간 경우. ForegroundServiceStartNotAllowedException이 여기에 속한다.
            giveUpForeground(error)
        }
        return START_NOT_STICKY
    }

    private fun giveUpForeground(error: Exception) {
        Log.w(TAG, "포그라운드 위치 서비스를 시작하지 못했습니다: ${error.javaClass.simpleName}")
        (application as MeetRouteApplication).container.onForegroundServiceFailed()
        stopSelf()
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.sharing_notification_title))
            .setContentText(getString(R.string.sharing_notification_text))
            .setOngoing(true)
            .build()

    override fun onTaskRemoved(rootIntent: Intent?) {
        (application as MeetRouteApplication).container.onTaskRemoved()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** 시작하지 못하면 false다. 앱이 백그라운드이면 시스템이 거부한다. */
        fun start(context: Context): Boolean = try {
            context.startService(Intent(context, LocationSharingService::class.java)) != null
        } catch (error: IllegalStateException) {
            false
        } catch (error: SecurityException) {
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationSharingService::class.java))
        }
    }
}
