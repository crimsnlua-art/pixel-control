package com.pixelrelay.sender

import android.content.Context
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pixelrelay.Graph
import com.pixelrelay.ui.SettingsStore
import java.util.concurrent.TimeUnit

/** 워커 1회: 발견·전송 후 필요하면 다음 워커를 예약하고 끝난다(포그라운드 서비스 없음). */
class SendWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Graph.init(applicationContext)
        if (Graph.settings.role() != SettingsStore.ROLE_SENDER) return Result.success()
        val force = inputData.getBoolean(SenderScheduler.KEY_FORCE, false)
        return try {
            val r = Graph.buildEngine().runOnce(force)
            SenderScheduler.afterRun(applicationContext, r)
            Result.success()
        } catch (e: Exception) {
            SenderScheduler.armTrigger(applicationContext)
            Result.retry()
        }
    }
}

object SenderScheduler {
    const val KEY_FORCE = "force"
    private const val PERIODIC = "pr-periodic"
    private const val TRIGGER = "pr-trigger"
    private const val NOW = "pr-now"
    private const val MORE = "pr-more"
    private const val RECHECK = "pr-recheck"

    /** 앱이 켜질 때·역할을 고른 뒤 호출: 15분 주기 스캔 + 갤러리 변화 감지 */
    fun ensure(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SendWorker>(15, TimeUnit.MINUTES).build(),
        )
        armTrigger(ctx)
    }

    /** MediaStore 의 사진·영상 변화를 감지해 깨어나는 작업(앱이 꺼져도 동작). */
    fun armTrigger(ctx: Context) {
        val c = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(5, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(60, TimeUnit.SECONDS)
            .build()
        val req = OneTimeWorkRequestBuilder<SendWorker>().setConstraints(c).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(TRIGGER, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /** "지금 보내기": 데이터 조건을 무시하고 바로 한 번 돌린다. */
    fun runNow(ctx: Context, force: Boolean) {
        val req = OneTimeWorkRequestBuilder<SendWorker>()
            .setInputData(Data.Builder().putBoolean(KEY_FORCE, force).build()).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
    }

    fun afterRun(ctx: Context, r: EngineReport) {
        val wm = WorkManager.getInstance(ctx)
        if (r.moreWork) wm.enqueueUniqueWork(MORE, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<SendWorker>().build())
        if (r.recheckSoon) {
            wm.enqueueUniqueWork(
                RECHECK, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SendWorker>().setInitialDelay(70, TimeUnit.SECONDS).build(),
            )
        }
        armTrigger(ctx)
    }
}
