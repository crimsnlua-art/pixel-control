package com.pixelrelay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pixelrelay.charge.WarningBook
import com.pixelrelay.core.MetaStore
import com.pixelrelay.core.PairingManager
import com.pixelrelay.core.RequestVerifier
import com.pixelrelay.core.SenderLedger
import com.pixelrelay.core.SystemClock
import com.pixelrelay.core.WarnCode
import com.pixelrelay.core.db.AppDb
import com.pixelrelay.drive.DriveApi
import com.pixelrelay.drive.GoogleTokenProvider
import com.pixelrelay.drive.RealDriveApi
import com.pixelrelay.receiver.AndroidDeviceProbe
import com.pixelrelay.receiver.PixelStorage
import com.pixelrelay.sender.AndroidNetworkState
import com.pixelrelay.sender.DailyUsage
import com.pixelrelay.sender.DrainRegistrar
import com.pixelrelay.sender.DriveSender
import com.pixelrelay.sender.LanSender
import com.pixelrelay.sender.LedgerSync
import com.pixelrelay.sender.MediaScanner
import com.pixelrelay.sender.MediaStoreSource
import com.pixelrelay.sender.NsdLanConnector
import com.pixelrelay.sender.RemoteStatusStore
import com.pixelrelay.sender.SenderCommands
import com.pixelrelay.sender.SenderEngine
import com.pixelrelay.sender.SenderNotifier
import com.pixelrelay.ui.DataStorePairedKeys
import com.pixelrelay.ui.SettingsStore

/** 알림 채널·헬퍼. */
object Notifs {
    const val CH_SERVICE = "pr_service"
    const val CH_WARN = "pr_warn"
    const val ID_SERVICE = 1001
    const val ID_AUTH = 1002
    private const val ID_WARN_BASE = 2000

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "픽셀중계 상시 동작", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_WARN, "픽셀중계 경고", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun serviceNotification(ctx: Context, text: String): Notification {
        ensureChannels(ctx)
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, com.pixelrelay.ui.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("픽셀중계 받기 동작 중")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    fun post(ctx: Context, id: Int, title: String, text: String) {
        ensureChannels(ctx)
        if (Build_SDK_33() && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, com.pixelrelay.ui.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CH_WARN)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }

    private fun Build_SDK_33() = android.os.Build.VERSION.SDK_INT >= 33

    fun warnId(code: String) = ID_WARN_BASE + (code.hashCode() and 0xFF)
}

class AndroidSenderNotifier(private val ctx: Context) : SenderNotifier {
    override fun pixelWarning(code: String, since: Long) {
        Notifs.post(ctx, Notifs.warnId(code), "픽셀 경고", WarnCode.korean(code))
    }

    override fun driveAuthNeeded() {
        Notifs.post(ctx, Notifs.ID_AUTH, "구글 드라이브 연결 필요", "앱을 열어 구글 계정 연결을 다시 승인해 주세요. 그 전까지는 집 밖 전송이 멈춥니다.")
    }
}

/** 앱 전체에서 쓰는 부품 모음(수동 의존성 주입). */
object Graph {
    lateinit var app: Context
        private set

    fun init(ctx: Context) {
        if (!::app.isInitialized) app = ctx.applicationContext
    }

    val clock = SystemClock
    val db: AppDb by lazy { AppDb.get(app) }
    val meta: MetaStore by lazy { MetaStore(db) }
    val settings: SettingsStore by lazy { SettingsStore(app) }
    val net by lazy { AndroidNetworkState(app) }
    val tokens by lazy { GoogleTokenProvider(app) }
    val drive: DriveApi by lazy { RealDriveApi(tokens) }

    // ---- 보내기 ----
    val senderLedger by lazy { SenderLedger(db, clock) }
    val mediaSource by lazy { MediaStoreSource(app) }
    val lanConnector by lazy { NsdLanConnector(app, settings, clock) }
    val remoteStatus by lazy { RemoteStatusStore(meta, clock) }
    val ledgerSync by lazy { LedgerSync(senderLedger, meta) }
    val senderCommands by lazy { SenderCommands(lanConnector, drive, net) { driveUsed() } }

    val scanner by lazy { MediaScanner(mediaSource, senderLedger, clock) { settings.senderBlocking() } }
    val trialRunner by lazy {
        com.pixelrelay.sender.FreeTrialRunner(com.pixelrelay.sender.TrialStore(meta), drive, mediaSource, scanner, senderLedger, remoteStatus, clock)
    }

    /** 구글 드라이브를 쓰는가(연결 안 함을 고르지 않았으면 true) */
    suspend fun driveUsed(): Boolean = (meta.getLong(com.pixelrelay.core.DriveUse.KEY_SKIPPED) ?: 0L) != 1L

    fun buildEngine(): SenderEngine {
        return SenderEngine(
            scanner = scanner,
            source = mediaSource,
            ledger = senderLedger,
            meta = meta,
            clock = clock,
            network = net,
            drive = drive,
            lan = lanConnector,
            lanSender = LanSender(mediaSource, senderLedger, clock),
            driveSender = DriveSender(mediaSource, senderLedger, drive, clock),
            settings = { settings.senderBlocking() },
            usage = DailyUsage(meta, clock),
            registrar = DrainRegistrar(meta, clock),
            sync = ledgerSync,
            statusStore = remoteStatus,
            notifier = AndroidSenderNotifier(app),
            commands = senderCommands,
            trialStep = { trialRunner.step() },
            driveEnabled = { driveUsed() },
        )
    }

    // ---- 받기 ----
    val recvLedger by lazy { com.pixelrelay.core.ReceiverLedger(db, clock) }
    val sink by lazy { PixelStorage.sink(app) }
    val probe by lazy { AndroidDeviceProbe(app) }
    val pairedKeys by lazy { DataStorePairedKeys(settings) }
    val pairing by lazy { PairingManager(clock, pairedKeys) }
    val verifier by lazy { RequestVerifier(clock, keys = { pairedKeys.all() }) }
    val warningBook by lazy { WarningBook(clock) }
    val receiver by lazy { com.pixelrelay.receiver.ReceiverRuntime(app) }
}
