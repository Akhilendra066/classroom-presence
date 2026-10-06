package com.classroompresence.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "checkpoints")
data class StoredCheckpoint(@PrimaryKey val key: String, val uid: String, val sessionId: String, val slot: Int,
    val json: String, val demo: Boolean, val syncState: String = "PENDING", val error: String = "")
@Entity(tableName = "cached")
data class CachedDocument(@PrimaryKey val key: String, val json: String)
@Entity(tableName = "calibration")
data class CalibrationSample(@PrimaryKey val id: String, val uid: String, val roomId: String,
    val label: String, val inside: Boolean, val capturedAtMs: Long, val device: String, val json: String)
@Dao interface LocalDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(point: StoredCheckpoint): Long
    @Query("SELECT * FROM checkpoints WHERE uid = :uid AND sessionId = :sessionId ORDER BY slot") fun observe(uid: String, sessionId: String): Flow<List<StoredCheckpoint>>
    @Query("SELECT * FROM checkpoints WHERE uid = :uid AND sessionId = :sessionId ORDER BY slot") suspend fun checkpoints(uid: String, sessionId: String): List<StoredCheckpoint>
    @Query("SELECT * FROM checkpoints WHERE uid = :uid AND demo = 0 AND syncState = 'PENDING' ORDER BY slot") suspend fun pending(uid: String): List<StoredCheckpoint>
    @Query("UPDATE checkpoints SET syncState = :state, error = :error WHERE `key` = :key") suspend fun mark(key: String, state: String, error: String = "")
    @Query("SELECT * FROM checkpoints WHERE sessionId = :sessionId ORDER BY uid, slot") suspend fun sessionPoints(sessionId: String): List<StoredCheckpoint>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun cache(document: CachedDocument)
    @Query("SELECT * FROM cached WHERE `key` = :key") suspend fun cached(key: String): CachedDocument?
    @Insert suspend fun calibration(sample: CalibrationSample)
    @Query("SELECT * FROM calibration WHERE uid = :uid AND roomId = :roomId ORDER BY capturedAtMs DESC") suspend fun calibrations(uid: String, roomId: String): List<CalibrationSample>
}
@Database(entities = [StoredCheckpoint::class, CachedDocument::class, CalibrationSample::class], version = 1, exportSchema = false)
abstract class LocalStore : RoomDatabase() {
    abstract fun dao(): LocalDao
    companion object {
        @Volatile private var instance: LocalStore? = null
        fun get(context: Context): LocalStore = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LocalStore::class.java, "presence.db").build().also { instance = it }
        }
    }
}
