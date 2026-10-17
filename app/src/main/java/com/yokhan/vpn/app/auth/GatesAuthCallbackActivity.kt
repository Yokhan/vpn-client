package com.yokhan.gates.app.auth

import android.app.Activity
import android.os.Bundle

// Only AppAuth's app-owned PendingIntent may deliver a completion here.
class GatesAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        YokhanKeycloakAuth.deliverAuthorizationResult(this, intent)
        finish()
    }
}
