package com.example.blessed3

import android.app.Application
import com.example.blessed3.db.AppDatabase

class BlueMeshApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DeviceIdentity.initialize(applicationContext)
        KnownPeers.initialize(applicationContext)
        ChatHistoryRepository.initialize(AppDatabase.build(this))
        BluetoothHandler.initialize(applicationContext)
        RelayManager.initialize(applicationContext)
        ChatTransportCoordinator.initialize(this)
        ForegroundServerPoller.start(this)
    }
}
