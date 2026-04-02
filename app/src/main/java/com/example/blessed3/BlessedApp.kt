package com.example.blessed3

import android.app.Application

class BlessedApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DeviceIdentity.initialize(applicationContext)
        KnownPeers.initialize(applicationContext)
        BluetoothHandler.initialize(applicationContext)
    }
}
