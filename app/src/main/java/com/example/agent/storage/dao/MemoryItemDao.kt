package com.example.agent.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.agent.storage.entity.MemoryItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryItemDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: MemoryItemEntity)

    @Query("SELECT * FROM memory_items WHERE id = :id")
    suspend fun findById(id: String): MemoryItemEntity?

    @Query("SELECT * FROM memory_items WHERE status != 'obsolete' ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<MemoryItemEntity>

    @Query("SELECT * FROM memory_items WHERE status != 'obsolete' ORDER BY updatedAt DESC LIMIT :limit")
    fun getRecentFlow(limit: Int): Flow<List<MemoryItemEntity>>

    @Query("SELECT * FROM memory_items WHERE type = :type AND status != 'obsolete' ORDER BY updatedAt DESC")
    suspend fun getByType(type: String): List<MemoryItemEntity>

    @Query("SELECT * FROM memory_items WHERE type = :type AND status != 'obsolete' ORDER BY updatedAt DESC")
    fun getByTypeFlow(type: String): Flow<List<MemoryItemEntity>>

    @Query("SELECT * FROM memory_items WHERE type = :type AND status = :status ORDER BY updatedAt DESC")
    suspend fun getByTypeAndStatus(type: String, status: String): List<MemoryItemEntity>

    @Query("SELECT COUNT(*) FROM memory_items WHERE type = :type AND status != 'obsolete'")
    suspend fun countByType(type: String): Int

    @Query("UPDATE memory_items SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, now: Long)

    @Query(
        "UPDATE memory_items SET useCount = useCount + 1, lastUsedAt = :now, updatedAt = :now WHERE id IN (:ids)"
    )
    suspend fun markUsed(ids: List<String>, now: Long)

    @Query("DELETE FROM memory_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM memory_items WHERE type = :type")
    suspend fun deleteByType(type: String)
}
