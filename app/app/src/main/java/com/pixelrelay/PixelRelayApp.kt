package com.pixelrelay

import android.app.Application
import com.pixelrelay.sender.SenderScheduler
import com.pixelrelay.ui.SettingsStore
import kotlinx.coroutines.runBlocking

class PixelRelayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Notifs.ensureChannels(this)
        runCatching { com.pixelrelay.update.UpdateManager.ensureScheduled(this) }   // 하루 1회 새 버전 확인(시험 환경처럼 WorkManager 가 없으면 건너뜀)
        // 보내기 역할이면 앱이 켜질 때마다 주기 스캔·갤러리 감지 작업이 걸려 있게 한다
        val role = runBlocking { SettingsStore(this@PixelRelayApp).role() }
        if (role == SettingsStore.ROLE_SENDER) SenderScheduler.ensure(this)
    }
}
