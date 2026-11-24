package com.pixelrelay.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pixelrelay.Graph
import com.pixelrelay.Notifs
import com.pixelrelay.core.UpdateException
import com.pixelrelay.core.UpdateInfo
import com.pixelrelay.core.UpdateRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** 새 버전 확인·내려받기·설치 화면 띄우기(보내기·받기 공통). 자동 다운로드·자동 설치는 하지 않는다. */
object UpdateManager {
    private const val K_AVAIL = "updateAvailable"      // 마지막으로 확인한 최신 version.json 원문
    private const val K_NOTIFIED = "updateNotifiedCode"
    private const val ID_UPDATE = 3001

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            // 리디렉션은 직접 따라가며 요청 전에 주소를 검사한다. 업데이트 경로는 평문(HTTP)을 쓰지 못하게 TLS 만 허용
            // (앱 기본 네트워크 설정은 집 LAN 의 사설 IP 통신 때문에 평문을 허용하지만 이 클라이언트에는 영향 없음).
            .followRedirects(false).followSslRedirects(false)
            .connectionSpecs(listOf(okhttp3.ConnectionSpec.MODERN_TLS, okhttp3.ConnectionSpec.COMPATIBLE_TLS)).build()
    }

    fun currentCode(ctx: Context): Int {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) p.longVersionCode.toInt() else @Suppress("DEPRECATION") p.versionCode
    }

    fun currentName(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** 저장해 둔 "새 버전" 정보(현재보다 새 것일 때만). */
    suspend fun available(ctx: Context): UpdateInfo? {
        val raw = Graph.meta.getString(K_AVAIL) ?: return null
        val info = UpdateRules.parse(raw) ?: return null
        return info.takeIf { UpdateRules.isNewer(it, currentCode(ctx)) }
    }

    /** version.json 을 받아 확인하고 결과를 저장한다. 새 버전이면 알림(버전당 1번). 실패하면 UpdateException(한글). */
    suspend fun check(ctx: Context, notify: Boolean = true): UpdateInfo? = withContext(Dispatchers.IO) {
        val raw = try {
            getChecked(UpdateRules.VERSION_URL) { r ->
                if (!r.isSuccessful) throw UpdateException("업데이트 정보를 받지 못했습니다(응답 ${r.code}).")
                val body = r.body ?: throw UpdateException("업데이트 정보가 비어 있습니다.")
                val bytes = body.byteStream().use { ins ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (out.size() <= UpdateRules.MAX_JSON_BYTES) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                    out.toByteArray()
                }
                if (bytes.size > UpdateRules.MAX_JSON_BYTES) throw UpdateException("업데이트 정보가 너무 큽니다.")
                String(bytes, Charsets.UTF_8)
            }
        } catch (e: UpdateException) {
            throw e
        } catch (e: Exception) {
            throw UpdateException("업데이트 정보를 받지 못했습니다. 인터넷 연결을 확인하세요.")
        }
        val info = UpdateRules.parse(raw) ?: throw UpdateException("업데이트 정보 형식이 올바르지 않습니다.")
        Graph.meta.putString(K_AVAIL, raw)
        if (!UpdateRules.isNewer(info, currentCode(ctx))) return@withContext null
        if (notify && (Graph.meta.getLong(K_NOTIFIED) ?: 0L) < info.versionCode) {
            Graph.meta.putLong(K_NOTIFIED, info.versionCode.toLong())
            Notifs.post(ctx, ID_UPDATE, "픽셀중계 ${info.versionName} 업데이트", "앱을 열어 설정 화면에서 업데이트하세요.")
        }
        info
    }

    /** 허용 주소만 최대 5번까지 리디렉션을 따라가 응답을 block 에 넘긴다. */
    private fun <T> getChecked(start: String, block: (okhttp3.Response) -> T): T {
        var cur = start
        repeat(UpdateRules.MAX_REDIRECTS + 1) {
            if (!UpdateRules.isAllowedUrl(cur)) throw UpdateException("업데이트 주소가 올바르지 않습니다.")
            val r = client.newCall(Request.Builder().url(cur).build()).execute()
            if (r.code in setOf(301, 302, 303, 307, 308)) {
                val loc = r.header("Location")
                r.close()
                cur = UpdateRules.resolveRedirect(cur, loc) ?: throw UpdateException("업데이트 주소가 올바르지 않습니다.")
            } else return r.use(block)
        }
        throw UpdateException("업데이트 주소가 너무 여러 번 넘어갑니다.")
    }

    private fun apkFile(ctx: Context) = File(ctx.filesDir, "update/pixelrelay.apk")

    /** APK 를 앱 내부 저장소로 받아 크기·SHA-256 을 확인한다. 어긋나면 지우고 UpdateException. */
    suspend fun download(ctx: Context, info: UpdateInfo): File = withContext(Dispatchers.IO) {
        val dest = apkFile(ctx)
        dest.parentFile?.mkdirs()
        dest.delete()
        try {
            getChecked(UpdateRules.APK_URL) { r ->
                if (!r.isSuccessful) throw UpdateException("업데이트 파일을 받지 못했습니다(응답 ${r.code}).")
                val body = r.body ?: throw UpdateException("업데이트 파일이 비어 있습니다.")
                UpdateRules.saveAndVerify(body.byteStream(), dest, info)
            }
        } catch (e: UpdateException) {
            dest.delete()
            throw e
        } catch (e: Exception) {
            dest.delete()
            throw UpdateException("업데이트 파일을 받지 못했습니다. 인터넷 연결을 확인하세요.")
        }
        dest
    }

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** "이 출처의 앱 설치 허용" 설정 화면. */
    fun openInstallPermissionSettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** PackageInstaller 세션으로 설치 확인 화면을 띄운다(사용자가 눌러야 설치됨, 무음 설치 아님). */
    suspend fun install(ctx: Context, apk: File) = withContext(Dispatchers.IO) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(apk.length())
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            apk.inputStream().use { ins -> s.openWrite("pixelrelay.apk", 0, apk.length()).use { out -> ins.copyTo(out); s.fsync(out) } }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallResultReceiver::class.java), flags)
            s.commit(pending.intentSender)
        }
    }

    fun ensureScheduled(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("pr-update-check", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

/** 하루 1회 새 버전 확인(네트워크 연결 조건). 내려받기·설치는 하지 않는다. */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Graph.init(applicationContext)
        runCatching { UpdateManager.check(applicationContext) }
        return Result.success()
    }
}

/** 설치 세션 결과: 사용자 확인이 필요하면 확인 화면을 띄우고, 실패하면 한글 알림. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val next = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                next?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val why = when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "설치를 취소했습니다."
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "설치된 앱과 서명이 달라 설치할 수 없습니다."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "저장 공간이 부족해 설치하지 못했습니다."
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "이 기기와 맞지 않는 버전입니다."
                    else -> "설치에 실패했습니다."
                }
                Notifs.post(context, 3002, "픽셀중계 업데이트 실패", why)
            }
        }
    }
}
