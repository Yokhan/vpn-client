package com.yokhan.gates.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import moe.matsuri.nb4a.utils.SendLog

class BlankActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // process crash log without blocking main UI thread
        intent?.getStringExtra("sendLog")?.let { logTitle ->
            val appContext = applicationContext
            runOnDefaultDispatcher {
                SendLog.sendLog(appContext, logTitle)
            }
        }

        finish()
    }

}