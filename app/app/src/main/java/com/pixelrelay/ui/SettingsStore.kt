package com.pixelrelay.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pixelrelay.core.PairedKeys
import com.pixelrelay.core.SenderSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.util.Base64

private val Context.prStore by preferencesDataStore("pixelrelay")

data class PairingInfo(val host: String, val keyBase64: String)

/** DataStore 에 보관하는 역할·설정·짝짓기 정보. */
class SettingsStore(private val ctx: Context) {
    private val ds get() = ctx.prStore

    companion object {
        const val ROLE_SENDER = "sender"
        const val ROLE_RECEIVER = "receiver"
        private val ROLE = stringPreferencesKey("role")
        private val ROLE_AT = longPreferencesKey("roleSetAtSec")
        private val WATCH = stringPreferencesKey("watchFolders")
        private val EXCLUDE = stringPreferencesKey("excludeFolders")
        private val PHOTO_MOBILE = booleanPreferencesKey("photoOverMobile")
        private val VIDEO_MOBILE = booleanPreferencesKey("videoOverMobile")
        private val DAILY_LIMIT = longPreferencesKey("dailyLimitBytes")
        private val PAIR_HOST = stringPreferencesKey("pairHost")
        private val PAIR_KEY = stringPreferencesKey("pairKey")
        private val RECV_KEYS = stringSetPreferencesKey("receiverPairedKeys")
    }

    // ---- 설정 마법사(역할별): 본 적 있는지, 수동 단계 체크 ----
    suspend fun wizardSeen(role: String): Boolean = ds.data.first()[booleanPreferencesKey("wizardSeen_$role")] ?: false
    suspend fun setWizardSeen(role: String, v: Boolean = true) { ds.edit { it[booleanPreferencesKey("wizardSeen_$role")] = v } }
    suspend fun wizardManual(role: String): Set<String> = ds.data.first()[stringSetPreferencesKey("wizardManual_$role")] ?: emptySet()
    suspend fun setWizardManual(role: String, id: String, on: Boolean) {
        ds.edit {
            val k = stringSetPreferencesKey("wizardManual_$role")
            val cur = it[k] ?: emptySet()
            it[k] = if (on) cur + id else cur - id
        }
    }

    val roleFlow: Flow<String?> = ds.data.map { it[ROLE] }
    suspend fun role(): String? = roleFlow.first()

    suspend fun setRole(role: String) {
        ds.edit {
            it[ROLE] = role
            // 보내기는 역할을 정한 시각 이후 파일만 보낸다
            if (role == ROLE_SENDER && it[ROLE_AT] == null) it[ROLE_AT] = System.currentTimeMillis() / 1000
        }
    }

    private fun parse(p: androidx.datastore.preferences.core.Preferences): SenderSettings {
        val d = SenderSettings()
        return SenderSettings(
            watchFolders = p[WATCH]?.split('\n')?.filter { it.isNotBlank() } ?: d.watchFolders,
            excludeFolders = p[EXCLUDE]?.split('\n')?.filter { it.isNotBlank() } ?: d.excludeFolders,
            photoOverMobile = p[PHOTO_MOBILE] ?: d.photoOverMobile,
            videoOverMobile = p[VIDEO_MOBILE] ?: d.videoOverMobile,
            dailyLimitBytes = p[DAILY_LIMIT] ?: d.dailyLimitBytes,
            roleSetAtSec = p[ROLE_AT] ?: 0L,
        )
    }

    val senderFlow: Flow<SenderSettings> = ds.data.map { parse(it) }
    suspend fun sender(): SenderSettings = senderFlow.first()
    fun senderBlocking(): SenderSettings = runBlocking { sender() }

    suspend fun saveSender(s: SenderSettings) {
        ds.edit {
            it[WATCH] = s.watchFolders.joinToString("\n")
            it[EXCLUDE] = s.excludeFolders.joinToString("\n")
            it[PHOTO_MOBILE] = s.photoOverMobile
            it[VIDEO_MOBILE] = s.videoOverMobile
            it[DAILY_LIMIT] = s.dailyLimitBytes
        }
    }

    val pairingFlow: Flow<PairingInfo?> = ds.data.map {
        val k = it[PAIR_KEY]
        if (k.isNullOrEmpty()) null else PairingInfo(it[PAIR_HOST].orEmpty(), k)
    }

    suspend fun pairing(): PairingInfo? = pairingFlow.first()

    suspend fun savePairing(host: String, keyBase64: String) {
        ds.edit { it[PAIR_HOST] = host; it[PAIR_KEY] = keyBase64 }
    }

    // 받기 쪽: 짝지은 키들
    suspend fun receiverKeys(): List<ByteArray> =
        (ds.data.first()[RECV_KEYS] ?: emptySet()).map { Base64.getDecoder().decode(it) }

    suspend fun addReceiverKey(key: ByteArray) {
        ds.edit { it[RECV_KEYS] = (it[RECV_KEYS] ?: emptySet()) + Base64.getEncoder().encodeToString(key) }
    }
}

/** 받는 쪽 서버가 쓰는 짝지은 키 저장소(DataStore 위). 서버 스레드에서 호출되므로 blocking. */
class DataStorePairedKeys(private val store: SettingsStore) : PairedKeys {
    @Volatile private var cache: List<ByteArray>? = null

    override fun add(deviceName: String, key: ByteArray) {
        runBlocking { store.addReceiverKey(key) }
        cache = null
    }

    override fun all(): List<ByteArray> = cache ?: runBlocking { store.receiverKeys() }.also { cache = it }
}

/**
 * Android 16+ 로컬 네트워크 권한. 컴파일 SDK 36 에 상수가 없을 수 있어 문자열로 다룬다.
 * 기기에 이 권한이 정의돼 있을 때만 요청하고, 거부되면 LAN 없이 드라이브만 쓴다(실기 확인 필요).
 */
object LocalNetworkPermission {
    const val NAME = "android.permission.ACCESS_LOCAL_NETWORK"

    fun definedOnDevice(ctx: Context): Boolean =
        runCatching { ctx.packageManager.getPermissionInfo(NAME, 0) }.isSuccess

    fun granted(ctx: Context): Boolean =
        !definedOnDevice(ctx) || ContextCompat.checkSelfPermission(ctx, NAME) == PackageManager.PERMISSION_GRANTED
}
