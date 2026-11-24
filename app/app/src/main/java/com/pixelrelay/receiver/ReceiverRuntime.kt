package com.pixelrelay.receiver

import android.content.Context
import android.database.ContentObserver
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import com.pixelrelay.Graph
import com.pixelrelay.charge.ChargeDecision
import com.pixelrelay.charge.ChargePolicy
import com.pixelrelay.charge.NoPlug
import com.pixelrelay.charge.PlugAction
import com.pixelrelay.charge.PlugController
import com.pixelrelay.core.Proto
import com.pixelrelay.core.RemoteCommand
import com.pixelrelay.drive.DriveAuthException
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

/**
 * 픽셀 상시 운영(설계서 9절): 서비스 안 코루틴 루프 모음.
 * LAN 서버, 드라이브 수신 루프, 상태 보고, 배터리·온도 기록, 포토 확인 대조.
 */
class ReceiverRuntime(private val ctx: Context) {
    private val g = Graph
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val confirmWake = Channel<Unit>(Channel.CONFLATED)

    val plug: PlugController = NoPlug
    val chargePolicy = ChargePolicy(g.clock)

    @Volatile var lastCharge: ChargeDecision? = null
        private set
    @Volatile var driveAuthProblem = false
        private set
    @Volatile var serverRunning = false
        private set

    val configStore = ReceiverConfigStore(g.meta)
    val cleaner = Cleaner(g.recvLedger, g.drive)
    val confirmer = PhotosConfirmer(g.recvLedger, g.sink, g.clock, cleaner)
    val core = ReceiverCore(g.recvLedger, g.sink, g.clock, stopped = { chargePolicy.receiveStopped })
    val commands = CommandHandler(g.recvLedger, configStore, confirmer, g.clock) { wake.trySend(Unit) }
    private val driveRx = DriveReceiver(g.drive, core, g.recvLedger, cleaner, { configStore.get() }, g.clock)

    val statusBuilder = StatusBuilder(
        ledger = g.recvLedger, sink = g.sink, probe = g.probe, clock = g.clock,
        config = { configStore.get() },
        chargeWarnings = { lastCharge?.active ?: emptySet() },
        driveAuthProblem = { driveAuthProblem },
        appVersion = "0.1.0",
        warningBook = g.warningBook,
    )

    private var server: LanServer? = null
    private var started = false
    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    private var observer: ContentObserver? = null

    @Synchronized
    fun start() {
        if (started) return
        started = true
        val s = LanServer(Proto.PORT, core, g.recvLedger, g.verifier, g.pairing, { statusBuilder.build() }, commands)
        s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        server = s
        serverRunning = true
        registerNsd()
        registerObserver()
        scope.launch { chargeLoop() }
        scope.launch { driveLoop() }
        scope.launch { reportLoop() }
        scope.launch { confirmLoop() }
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        serverRunning = false
        runCatching { server?.stop() }
        nsdListener?.let { runCatching { nsd?.unregisterService(it) } }
        observer?.let { ctx.contentResolver.unregisterContentObserver(it) }
        scope.cancel()
    }

    private fun registerNsd() {
        val info = NsdServiceInfo().apply {
            serviceName = Proto.SERVICE_NAME
            serviceType = Proto.SERVICE_TYPE
            port = Proto.PORT
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsdListener = l
        runCatching { nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    private fun registerObserver() {
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { confirmWake.trySend(Unit) }
        }
        observer = o
        ctx.contentResolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, o)
    }

    private fun <T> withWakeLock(block: suspend () -> T): suspend () -> T = {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pixelrelay:work")
        wl.acquire(10 * 60_000L)
        try { block() } finally { if (wl.isHeld) wl.release() }
    }

    /** 배터리 구동일 때는 폴링 간격을 2배로 (설계서 9절) */
    private fun slow(): Int = if (g.probe.charging()) 1 else 2

    // ---- 배터리·온도 1분마다 기록, 충전 정책 ----
    private suspend fun chargeLoop() {
        val log = File(ctx.filesDir, "battery.log")
        while (scope.isActive) {
            try {
                val pct = g.probe.batteryPct(); val chg = g.probe.charging(); val t = g.probe.tempC()
                val d = chargePolicy.evaluate(pct, chg, t)
                lastCharge = d
                if (plug.available) when (d.plugAction) {
                    PlugAction.TURN_OFF -> plug.turnOff()
                    PlugAction.TURN_ON -> plug.turnOn()
                    PlugAction.KEEP -> {}
                }
                if (log.length() > 200_000) log.writeText("")
                log.appendText("${g.clock.nowMs()},$pct,$chg,$t\n")
            } catch (e: Exception) { /* 기록 실패는 무시 */ }
            delay(60_000L)
        }
    }

    // ---- 드라이브 수신 루프 (바로 2분 / 용량 10분, 명령 확인 2분) ----
    private suspend fun driveLoop() {
        var lastList = 0L
        var lastCmd = 0L
        while (scope.isActive) {
            try {
                if (!chargePolicy.receiveStopped) {
                    val now = g.clock.nowMs()
                    val cfg = configStore.get()
                    val m = slow()
                    if (now - lastCmd >= 2 * 60_000L * m) {
                        lastCmd = now
                        withWakeLock { pollCommands() }()
                    }
                    val forced = driveRx.forcedNow()
                    if (forced || now - lastList >= ReceivePolicy.listIntervalMs(cfg.queueMode) * m) {
                        lastList = now
                        withWakeLock { driveRx.runOnce() }()
                        driveAuthProblem = false
                    }
                }
            } catch (e: DriveAuthException) {
                driveAuthProblem = true
            } catch (e: Exception) {
                // 다음 회차에 다시
            }
            withTimeoutOrNull(30_000L) { wake.receive() }
        }
    }

    private suspend fun pollCommands() {
        for (f in g.drive.listCommands()) {
            val cmd = runCatching { RemoteCommand.fromJson(JSONObject(g.drive.readCommand(f.id))) }.getOrNull()
            if (cmd != null) {
                val r = commands.handle(cmd, remote = true)
                commands.recordResult(cmd, r)
            }
            g.drive.deleteCommand(f.id)
        }
    }

    // ---- 상태 보고: 5분마다 + 변화 때(최소 1분) ----
    private suspend fun reportLoop() {
        var last = 0L
        var lastSig = ""
        while (scope.isActive) {
            try {
                val now = g.clock.nowMs()
                val sig = "${g.recvLedger.maxSeq()}|${g.recvLedger.getLong(com.pixelrelay.core.TrialSignals.KEY_PHOTOS_DONE)}|${g.recvLedger.getLong(PhotosConfirmer.KEY_BUTTON_AT)}|${(lastCharge?.active ?: emptySet()).sorted()}|$driveAuthProblem"
                val due = now - last >= 5 * 60_000L * slow()
                val changed = sig != lastSig && now - last >= 60_000L
                if (due || changed) {
                    last = now; lastSig = sig
                    g.drive.putStatus(statusBuilder.buildDriveDoc(commands.results()).toString())
                }
            } catch (e: DriveAuthException) {
                driveAuthProblem = true
            } catch (e: Exception) { /* 다음에 */ }
            delay(20_000L)
        }
    }

    // ---- 포토 확인 대조: 10분 주기 + MediaStore 변화 때 ----
    private suspend fun confirmLoop() {
        while (scope.isActive) {
            try {
                val r = confirmer.check()
                if (r.missingNow > 0) {
                    delay(65_000L) // 1분 뒤 재확인
                    confirmer.check()
                }
                cleaner.cleanAllConfirmed()
            } catch (e: Exception) { /* 다음에 */ }
            withTimeoutOrNull(10 * 60_000L * slow()) { confirmWake.receive() }
            delay(3_000L)
        }
    }
}
