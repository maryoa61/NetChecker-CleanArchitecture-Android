package com.netchecker.data.database

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CleanIpDao {
    @Query("SELECT * FROM clean_ips ORDER BY pingMs ASC")
    fun getAllCleanIpsFlow(): Flow<List<CleanIpEntity>>

    @Query("SELECT * FROM clean_ips ORDER BY pingMs ASC")
    suspend fun getAllCleanIps(): List<CleanIpEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCleanIps(vararg ip: CleanIpEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCleanIpList(ips: List<CleanIpEntity>)

    @Query("DELETE FROM clean_ips WHERE ipAddress = :ipAddress")
    suspend fun deleteCleanIp(ipAddress: String)

    @Query("DELETE FROM clean_ips")
    suspend fun clearAllCleanIps()
}

@Dao
interface ProxyConfigDao {
    @Query("SELECT * FROM proxy_configs ORDER BY id DESC")
    fun getAllProxiesFlow(): Flow<List<ProxyConfigEntity>>

    @Query("SELECT * FROM proxy_configs ORDER BY id DESC")
    suspend fun getAllProxies(): List<ProxyConfigEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProxy(proxy: ProxyConfigEntity): Long

    @Update
    suspend fun updateProxy(proxy: ProxyConfigEntity)

    @Query("DELETE FROM proxy_configs WHERE id = :id")
    suspend fun deleteProxy(id: Int)

    @Query("DELETE FROM proxy_configs")
    suspend fun clearConfigurations()
}

@Database(entities = [CleanIpEntity::class, ProxyConfigEntity::class], version = 1, exportSchema = false)
abstract class NetCheckerDatabase : RoomDatabase() {

    abstract fun cleanIpDao(): CleanIpDao
    abstract fun proxyConfigDao(): ProxyConfigDao

    companion object {
        @Volatile
        private var INSTANCE: NetCheckerDatabase? = null

        fun getDatabase(context: Context): NetCheckerDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NetCheckerDatabase::class.java,
                    "net_checker_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
