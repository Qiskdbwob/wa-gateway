package com.example.agent.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.agent.storage.entity.ProviderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderDao {

  @Query("SELECT * FROM providers ORDER BY sortOrder ASC, createdAt ASC")
  fun observeAll(): Flow<List<ProviderEntity>>

  @Query("SELECT * FROM providers ORDER BY sortOrder ASC, createdAt ASC")
  suspend fun getAll(): List<ProviderEntity>

  @Query("SELECT COUNT(*) FROM providers")
  suspend fun count(): Int

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(provider: ProviderEntity)

  @Query("DELETE FROM providers WHERE id = :id")
  suspend fun delete(id: String)
}
