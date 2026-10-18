package com.ksjd.testem

import android.app.Application

/** Runs first in every process start, also when only the tracking service or an alarm starts the app. */
class PlanRideApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.ksjd.testem.hub.AppLogs.installCrashRecorder(this)
        com.ksjd.testem.offline.OfflineTimetables.init(this)
    }
}
