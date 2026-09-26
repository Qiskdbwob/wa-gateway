package com.example.agent.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.agent.storage.entity.ApprovalRequestEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ApprovalRequestDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(request: ApprovalRequestEntity)

    @Query("SELECT * FROM approval_requests WHERE id = :id")
    suspend fun findById(id: String): ApprovalRequestEntity?

    @Query("SELECT * FROM approval_requests WHERE id = :id")
    fun findByIdFlow(id: String): Flow<ApprovalRequestEntity?>

    @Query("SELECT * FROM approval_requests WHERE status = 'PENDING' ORDER BY requestedAt DESC")
    suspend fun getPending(): List<ApprovalRequestEntity>

    @Query("SELECT * FROM approval_requests WHERE status = 'PENDING' ORDER BY requestedAt DESC")
    fun getPendingFlow(): Flow<List<ApprovalRequestEntity>>

    @Query("SELECT * FROM approval_requests WHERE conversationId = :conversationId AND status = 'PENDING' ORDER BY requestedAt DESC")
    suspend fun getPendingForConversation(conversationId: String): List<ApprovalRequestEntity>

    @Query("UPDATE approval_requests SET status = :status, resolvedAt = :resolvedAt WHERE id = :id")
    suspend fun resolve(id: String, status: String, resolvedAt: Long)

    @Query("UPDATE approval_requests SET status = 'EXPIRED', resolvedAt = :now WHERE status = 'PENDING' AND requestedAt < :cutoff")
    suspend fun expireOld(cutoff: Long, now: Long)

    @Query("DELETE FROM approval_requests WHERE resolvedAt IS NOT NULL AND resolvedAt < :cutoff")
    suspend fun purgeResolvedBefore(cutoff: Long)
}
