package com.example.agent.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.agent.storage.entity.ContactRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactRuleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: ContactRuleEntity)

    @Query("SELECT * FROM contact_rules WHERE contactId = :contactId LIMIT 1")
    suspend fun findByContactId(contactId: String): ContactRuleEntity?

    @Query("SELECT * FROM contact_rules WHERE mode = :mode ORDER BY createdAt DESC")
    suspend fun getByMode(mode: String): List<ContactRuleEntity>

    @Query("SELECT * FROM contact_rules WHERE mode = :mode ORDER BY createdAt DESC")
    fun getByModeFlow(mode: String): Flow<List<ContactRuleEntity>>

    @Query("SELECT * FROM contact_rules")
    suspend fun getAll(): List<ContactRuleEntity>

    @Query("DELETE FROM contact_rules WHERE contactId = :contactId")
    suspend fun deleteByContactId(contactId: String)

    @Query("DELETE FROM contact_rules")
    suspend fun clearAll()
}
