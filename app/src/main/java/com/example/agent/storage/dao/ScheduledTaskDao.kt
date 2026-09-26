package com.example.agent.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.agent.storage.entity.ScheduledTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledTaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: ScheduledTaskEntity)

    @Query("SELECT * FROM scheduled_tasks WHERE id = :id")
    suspend fun findById(id: String): ScheduledTaskEntity?

    @Query("SELECT * FROM scheduled_tasks ORDER BY createdAt DESC")
    suspend fun getAll(): List<ScheduledTaskEntity>

    @Query("SELECT * FROM scheduled_tasks ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<ScheduledTaskEntity>>

    @Query("SELECT * FROM scheduled_tasks WHERE enabled = 1")
    suspend fun getEnabled(): List<ScheduledTaskEntity>

    @Query("UPDATE scheduled_tasks SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("UPDATE scheduled_tasks SET lastRunAt = :runAt, nextRunAt = :nextRunAt, lastStatus = :status, lastResult = :result, lastError = :error WHERE id = :id")
    suspend fun recordRun(id: String, runAt: Long, nextRunAt: Long?, status: String?, result: String?, error: String?)

    @Query("DELETE FROM scheduled_tasks WHERE id = :id")
    suspend fun deleteById(id: String)
}
