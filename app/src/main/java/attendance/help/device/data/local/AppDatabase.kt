package attendance.help.device.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "peer_history")
data class PeerEntity(
    @PrimaryKey val deviceId: String,
    val displayName: String,
    val tailscaleIp: String,
    val lastConnectedAtEpochMs: Long
)

@Dao
interface PeerDao {
    @Query("SELECT * FROM peer_history ORDER BY lastConnectedAtEpochMs DESC")
    suspend fun getAll(): List<PeerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(peer: PeerEntity)
}

@Database(entities = [PeerEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao
}
