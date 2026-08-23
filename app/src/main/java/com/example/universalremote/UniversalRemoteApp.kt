package com.example.universalremote

import android.app.Application
import com.example.universalremote.network.LocalEndpointPolicy

class UniversalRemoteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LocalEndpointPolicy.initialize(this)
    }
}
