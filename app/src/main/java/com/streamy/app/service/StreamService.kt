package com.streamy.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

class StreamService : Service() {
    // TODO(Task 11): real implementation (foreground service, wakelocks, audio pipeline)
    override fun onBind(intent: Intent?): IBinder? = null
}
