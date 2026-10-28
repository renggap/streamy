package com.streamy.app

import android.app.Application
import com.streamy.app.storage.RecordingStorage

class StreamyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Recover recordings stranded IS_PENDING by a previous process death. Safe only here,
        // before any in-process recorder could have opened a new pending file.
        val startSec = System.currentTimeMillis() / 1000
        Thread({ RecordingStorage.publishOrphans(this, startSec) }, "streamy-orphan-sweep")
            .apply { isDaemon = true }.start()
    }
}
