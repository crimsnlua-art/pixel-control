package com.pixelrelay.core.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** 보내기 장부 한 줄. 키 = 원본 SHA-256. 해시 전에 건너뛴 파일은 키가 "m{mediaId}" 이다. */
@Entity(tableName = "send_entry")
data class SendEntry(
    @PrimaryKey val sha: String,
    val mediaId: Long,
    val uri: String,
    val name: String,
    val size: Long,
    val takenAt: Long,
    val mime: String,
    val relPath: String,
    val isVideo: Boolean,
    val state: String,
    val reason: String? = null,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
    val route: String? = null,
    val lanOffset: Long = 0,
    val driveSessionUri: String? = null,
    val driveOffset: Long = 0,
    val driveFileId: String? = null,
    val createdAt: Long,
    val stateAt: Long,
    /** 등록 때의 MediaStore DATE_MODIFIED(초). 0 = 모름. 전송 직전 재검사에 쓴다. */
    val dateModifiedSec: Long = 0,
    /** 다시 보내기 흐름 중인 항목의 명령 ID(영속). 도착·확정·건너뜀·실패가 되면 비운다. */
    val resendCommandId: String? = null,
    /** 무료 업로드 확인 시험 파일 표시 */
    val trial: Boolean = false,
) {
    val stateEnum: com.pixelrelay.core.SendState get() = com.pixelrelay.core.SendState.valueOf(state)
}

/** 감지한 미디어 파일의 안정 조건 추적(해시 전). */
@Entity(tableName = "seen_media")
data class SeenMedia(
    @PrimaryKey val mediaId: Long,
    val size: Long,
    val dateModified: Long,
    val stableSince: Long,
    val sha: String? = null,
)

/** 받기 장부 한 줄. */
@Entity(tableName = "recv_entry")
data class RecvEntry(
    @PrimaryKey val sha: String,
    val name: String,
    val size: Long,
    val takenAt: Long,
    val mime: String,
    val via: String,
    val path: String? = null,
    val mediaId: Long? = null,
    val state: String,
    val reason: String? = null,
    val failCount: Int = 0,
    val tReceiving: Long = 0,
    val tPublished: Long? = null,
    val tConfirmed: Long? = null,
    val tCleaned: Long? = null,
    val tVanished: Long? = null,
    val missingSince: Long? = null,
    val ownDeleted: Boolean = false,
    val seq: Long = 0,
    /** "포토 공간 확보 열기" 누른 순간 파일·MediaStore 존재가 확인된 시각(후보). */
    val candidateAt: Long? = null,
) {
    val stateEnum: com.pixelrelay.core.RecvState get() = com.pixelrelay.core.RecvState.valueOf(state)
}

/** 드라이브 사본 표: 같은 sha 의 사본이 여러 개일 수 있다. */
@Entity(tableName = "drive_copy", primaryKeys = ["sha", "driveFileId"])
data class DriveCopy(
    val sha: String,
    val driveFileId: String,
    val size: Long = 0,
)

/** 받기 쪽 변화 기록. seq 는 빈틈 없이 단조 증가(AUTOINCREMENT). */
@Entity(tableName = "recv_change")
data class RecvChange(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val sha: String,
    val state: String,
    val at: Long,
)

@Entity(tableName = "meta")
data class Meta(
    @PrimaryKey val k: String,
    val v: String,
)

@Dao
interface SendDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(e: SendEntry): Long

    @Update
    suspend fun update(e: SendEntry)

    /** 진행 정보만 갱신: 끝난 상태로 되돌리지 않도록 미완료 상태일 때만. */
    @Query("UPDATE send_entry SET lanOffset = :off WHERE sha = :sha AND state IN ('WAITING','SENDING_LAN','UPLOADING_DRIVE')")
    suspend fun setLanOffset(sha: String, off: Long)

    @Query("UPDATE send_entry SET driveOffset = :off WHERE sha = :sha AND state IN ('WAITING','SENDING_LAN','UPLOADING_DRIVE')")
    suspend fun setDriveOffset(sha: String, off: Long)

    @Query("SELECT * FROM send_entry WHERE sha = :sha")
    suspend fun get(sha: String): SendEntry?

    @Query("DELETE FROM send_entry WHERE sha = :sha")
    suspend fun delete(sha: String)

    @Query("SELECT * FROM send_entry ORDER BY takenAt DESC")
    fun observeAll(): Flow<List<SendEntry>>

    @Query("SELECT * FROM send_entry")
    suspend fun all(): List<SendEntry>

    @Query("SELECT * FROM send_entry WHERE state IN ('WAITING','SENDING_LAN','UPLOADING_DRIVE') AND nextAttemptAt <= :now ORDER BY takenAt DESC")
    suspend fun sendable(now: Long): List<SendEntry>

    @Query("SELECT * FROM send_entry WHERE state IN ('WAITING','SENDING_LAN','UPLOADING_DRIVE')")
    suspend fun unfinished(): List<SendEntry>

    @Query("SELECT state, COUNT(*) AS n FROM send_entry GROUP BY state")
    fun observeCounts(): Flow<List<StateCount>>

    @Query("SELECT * FROM send_entry WHERE state = :state ORDER BY takenAt DESC")
    suspend fun byState(state: String): List<SendEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSeen(s: SeenMedia)

    @Query("SELECT * FROM seen_media WHERE mediaId = :id")
    suspend fun seen(id: Long): SeenMedia?

    @Query("DELETE FROM seen_media WHERE mediaId = :id")
    suspend fun deleteSeen(id: Long)

    @Query("SELECT COUNT(*) FROM seen_media WHERE sha IS NULL")
    fun observeRecordingCount(): Flow<Int>
}

data class StateCount(val state: String, val n: Int)

@Dao
interface RecvDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(e: RecvEntry): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: RecvEntry)

    @Query("DELETE FROM recv_entry WHERE sha = :sha")
    suspend fun delete(sha: String)

    @Query("SELECT * FROM recv_entry WHERE sha = :sha")
    suspend fun get(sha: String): RecvEntry?

    @Query("SELECT * FROM recv_entry ORDER BY tReceiving DESC")
    fun observeAll(): Flow<List<RecvEntry>>

    @Query("SELECT * FROM recv_entry WHERE state = :state")
    suspend fun byState(state: String): List<RecvEntry>

    @Query("SELECT * FROM recv_entry")
    suspend fun all(): List<RecvEntry>

    @Query("SELECT state, COUNT(*) AS n FROM recv_entry GROUP BY state")
    suspend fun counts(): List<StateCount>

    @Query("SELECT MAX(MAX(IFNULL(tConfirmed,0)), MAX(IFNULL(tVanished,0))) FROM recv_entry")
    suspend fun lastPhotosActivity(): Long?

    @Query("SELECT MIN(tPublished) FROM recv_entry WHERE state = 'PUBLISHED'")
    suspend fun oldestPublished(): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCopy(c: DriveCopy)

    @Query("DELETE FROM drive_copy WHERE sha = :sha AND driveFileId = :id")
    suspend fun deleteCopy(sha: String, id: String)

    @Query("SELECT * FROM drive_copy WHERE sha = :sha")
    suspend fun copies(sha: String): List<DriveCopy>

    @Insert
    suspend fun insertChange(c: RecvChange): Long

    @Query("SELECT * FROM recv_change WHERE seq > :since ORDER BY seq ASC LIMIT :limit")
    suspend fun changesSince(since: Long, limit: Int): List<RecvChange>

    @Query("SELECT IFNULL(MAX(seq),0) FROM recv_change")
    suspend fun maxSeq(): Long

    @Query("SELECT * FROM meta WHERE k = :k")
    suspend fun meta(k: String): Meta?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMeta(m: Meta)
}

@Database(
    entities = [SendEntry::class, SeenMedia::class, RecvEntry::class, DriveCopy::class, RecvChange::class, Meta::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDb : RoomDatabase() {
    abstract fun send(): SendDao
    abstract fun recv(): RecvDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "pixelrelay.db")
                .build().also { inst = it }
        }
    }
}
