package com.example.blessed3

import android.app.Application
import com.example.blessed3.db.AppDatabase

class BlessedApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DeviceIdentity.initialize(applicationContext)
        PeerPublicKeyStore.initialize(applicationContext)
        E2eeIdentity.initialize(applicationContext)
        com.google.crypto.tink.config.TinkConfig.register()
        KnownPeers.initialize(applicationContext)
        ChatHistoryRepository.initialize(AppDatabase.build(this))
        BluetoothHandler.initialize(applicationContext)
        RelayManager.initialize(applicationContext)
        ChatTransportCoordinator.initialize(this)
        ForegroundServerPoller.start(this)
    }
}
