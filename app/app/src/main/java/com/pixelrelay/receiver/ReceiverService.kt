package com.pixelrelay.receiver

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.pixelrelay.Graph
import com.pixelrelay.Notifs
import com.pixelrelay.ui.SettingsStore
import kotlinx.coroutines.runBlocking

/**
 * 픽셀 받기 포그라운드 서비스(유형 dataSync, 상시). WifiLock(FULL_HIGH_PERF) 상시 유지.
 * 받기는 API 29 이하에서만 고를 수 있다(설계서 2절).
 */
class ReceiverService : Service() {
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Graph.init(applicationContext)
        val n = Notifs.serviceNotification(this, "서버 동작 중")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifs.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(Notifs.ID_SERVICE, n)
        }
        @Suppress("DEPRECATION")
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "pixelrelay:wifi").also {
            it.setReferenceCounted(false); it.acquire()
        }
        Graph.receiver.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        Graph.receiver.stop()
        wifiLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, ReceiverService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ReceiverService::class.java))
        }
    }
}

/** 부팅하면(잠금 없이) 받기 역할일 때 서비스를 다시 시작한다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 부팅 또는 앱 업데이트(MY_PACKAGE_REPLACED) 뒤: 받기 역할이면 서비스를, 보내기 역할이면 전송 작업을 다시 건다
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val role = runBlocking { SettingsStore(context).role() }
        if (role == SettingsStore.ROLE_RECEIVER) ReceiverService.start(context)
        else if (role == SettingsStore.ROLE_SENDER && intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) com.pixelrelay.sender.SenderScheduler.ensure(context)
    }
}
