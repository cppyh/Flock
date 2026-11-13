package com.meetroute.app

import android.app.Application
import com.meetroute.app.di.AppContainer

class MeetRouteApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
