package com.example.agent

import com.example.agent.storage.dao.AgentTaskDao
import com.example.agent.storage.dao.ApprovalRequestDao
import com.example.agent.storage.dao.ContactRuleDao
import com.example.agent.storage.dao.MemoryItemDao
import com.example.agent.storage.dao.ScheduledTaskDao
import com.example.agent.storage.entity.AgentTaskEntity
import com.example.agent.storage.entity.ApprovalRequestEntity
import com.example.agent.storage.entity.ContactRuleEntity
import com.example.agent.storage.entity.MemoryItemEntity
import com.example.agent.storage.entity.ScheduledTaskEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory DAO fakes so the new priorities (access control, memory, approvals,
 * scheduler, subagents) can be tested without Room/Robolectric. They implement the real
 * DAO interfaces, so a query added to a DAO shows up here as a compile error instead of a
 * silently missing test.
 */
class FakeContactRuleDao : ContactRuleDao {
    val rules = LinkedHashMap<String, ContactRuleEntity>()

    override suspend fun upsert(rule: ContactRuleEntity) {
        rules[rule.contactId] = rule
    }

    override suspend fun findByContactId(contactId: String): ContactRuleEntity? = rules[contactId]

    override suspend fun getByMode(mode: String): List<ContactRuleEntity> =
        rules.values.filter { it.mode == mode }

    override fun getByModeFlow(mode: String): Flow<List<ContactRuleEntity>> =
        MutableStateFlow(rules.values.filter { it.mode == mode })

    override suspend fun getAll(): List<ContactRuleEntity> = rules.values.toList()

    override suspend fun deleteByContactId(contactId: String) {
        rules.remove(contactId)
    }

    override suspend fun clearAll() {
        rules.clear()
    }
}

class FakeMemoryItemDao : MemoryItemDao {
    val items = LinkedHashMap<String, MemoryItemEntity>()
    var lastMarkedUsed: List<String> = emptyList()

    override suspend fun upsert(item: MemoryItemEntity) {
        items[item.id] = item
    }

    override suspend fun findById(id: String): MemoryItemEntity? = items[id]

    override suspend fun getRecent(limit: Int): List<MemoryItemEntity> =
        items.values.filter { it.status != MemoryItemEntity.STATUS_OBSOLETE }
            .sortedByDescending { it.updatedAt }
            .take(limit)

    override fun getRecentFlow(limit: Int): Flow<List<MemoryItemEntity>> =
        MutableStateFlow(getRecentSync(limit))

    private fun getRecentSync(limit: Int): List<MemoryItemEntity> =
        items.values.sortedByDescending { it.updatedAt }.take(limit)

    override suspend fun getByType(type: String): List<MemoryItemEntity> =
        items.values.filter { it.type == type && it.status != MemoryItemEntity.STATUS_OBSOLETE }

    override fun getByTypeFlow(type: String): Flow<List<MemoryItemEntity>> =
        MutableStateFlow(items.values.filter { it.type == type })

    override suspend fun getByTypeAndStatus(type: String, status: String): List<MemoryItemEntity> =
        items.values.filter { it.type == type && it.status == status }

    override suspend fun countByType(type: String): Int = getByType(type).size

    override suspend fun updateStatus(id: String, status: String, now: Long) {
        items[id]?.let { items[id] = it.copy(status = status, updatedAt = now) }
    }

    override suspend fun markUsed(ids: List<String>, now: Long) {
        lastMarkedUsed = ids
        ids.forEach { id ->
            items[id]?.let {
                items[id] = it.copy(useCount = it.useCount + 1, lastUsedAt = now, updatedAt = now)
            }
        }
    }

    override suspend fun deleteById(id: String) {
        items.remove(id)
    }

    override suspend fun deleteByType(type: String) {
        items.values.filter { it.type == type }.forEach { items.remove(it.id) }
    }

    /** Mirrors the SQL: body *or* keywords, obsolete rows excluded, newest first. */
    override suspend fun searchItems(term: String, limit: Int): List<MemoryItemEntity> {
        val needle = term.lowercase()
        return items.values
            .filter { it.status != MemoryItemEntity.STATUS_OBSOLETE }
            .filter { it.content.lowercase().contains(needle) || it.keywords.lowercase().contains(needle) }
            .sortedByDescending { it.updatedAt }
            .take(limit)
    }
}

class FakeApprovalRequestDao : ApprovalRequestDao {
    val requests = LinkedHashMap<String, ApprovalRequestEntity>()

    override suspend fun upsert(request: ApprovalRequestEntity) {
        requests[request.id] = request
    }

    override suspend fun findById(id: String): ApprovalRequestEntity? = requests[id]

    override fun findByIdFlow(id: String): Flow<ApprovalRequestEntity?> =
        MutableStateFlow(requests[id])

    override suspend fun getPending(): List<ApprovalRequestEntity> =
        requests.values.filter { it.status == ApprovalRequestEntity.STATUS_PENDING }

    override fun getPendingFlow(): Flow<List<ApprovalRequestEntity>> =
        MutableStateFlow(getPendingSync())

    private fun getPendingSync(): List<ApprovalRequestEntity> =
        requests.values.filter { it.status == ApprovalRequestEntity.STATUS_PENDING }

    override suspend fun getPendingForConversation(conversationId: String): List<ApprovalRequestEntity> =
        requests.values.filter {
            it.status == ApprovalRequestEntity.STATUS_PENDING && it.conversationId == conversationId
        }

    override suspend fun resolve(id: String, status: String, resolvedAt: Long) {
        requests[id]?.let { requests[id] = it.copy(status = status, resolvedAt = resolvedAt) }
    }

    override suspend fun expireOld(cutoff: Long, now: Long) {
        requests.values.filter {
            it.status == ApprovalRequestEntity.STATUS_PENDING && it.requestedAt < cutoff
        }.forEach { requests[it.id] = it.copy(status = ApprovalRequestEntity.STATUS_EXPIRED, resolvedAt = now) }
    }

    override suspend fun purgeResolvedBefore(cutoff: Long) {
        requests.values.filter { (it.resolvedAt ?: Long.MAX_VALUE) < cutoff }
            .forEach { requests.remove(it.id) }
    }
}

class FakeScheduledTaskDao : ScheduledTaskDao {
    val tasks = LinkedHashMap<String, ScheduledTaskEntity>()
    val runHistory = mutableListOf<Pair<String, String?>>()

    override suspend fun upsert(task: ScheduledTaskEntity) {
        tasks[task.id] = task
    }

    override suspend fun findById(id: String): ScheduledTaskEntity? = tasks[id]

    override suspend fun getAll(): List<ScheduledTaskEntity> = tasks.values.toList()

    override fun getAllFlow(): Flow<List<ScheduledTaskEntity>> = MutableStateFlow(tasks.values.toList())

    override suspend fun getEnabled(): List<ScheduledTaskEntity> =
        tasks.values.filter { it.enabled }

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        tasks[id]?.let { tasks[id] = it.copy(enabled = enabled) }
    }

    override suspend fun recordRun(
        id: String,
        runAt: Long,
        nextRunAt: Long?,
        status: String?,
        result: String?,
        error: String?
    ) {
        tasks[id]?.let {
            tasks[id] = it.copy(
                lastRunAt = runAt,
                nextRunAt = nextRunAt,
                lastStatus = status,
                lastResult = result,
                lastError = error
            )
        }
        runHistory.add(id to status)
    }

    override suspend fun deleteById(id: String) {
        tasks.remove(id)
    }
}

class FakeAgentTaskDao : AgentTaskDao {
    val tasks = LinkedHashMap<String, AgentTaskEntity>()

    override suspend fun upsert(task: AgentTaskEntity) {
        tasks[task.id] = task
    }

    override suspend fun findById(id: String): AgentTaskEntity? = tasks[id]

    override suspend fun getRecent(limit: Int): List<AgentTaskEntity> =
        tasks.values.sortedByDescending { it.createdAt }.take(limit)

    override fun getRecentFlow(limit: Int): Flow<List<AgentTaskEntity>> =
        MutableStateFlow(tasks.values.sortedByDescending { it.createdAt }.take(limit))

    override fun getActiveFlow(): Flow<List<AgentTaskEntity>> = MutableStateFlow(
        tasks.values.filter {
            it.status == AgentTaskEntity.STATUS_QUEUED || it.status == AgentTaskEntity.STATUS_RUNNING
        }
    )

    override suspend fun markRunning(id: String, status: String, startedAt: Long) {
        tasks[id]?.let { tasks[id] = it.copy(status = status, startedAt = startedAt) }
    }

    override suspend fun markFinished(
        id: String,
        status: String,
        completedAt: Long,
        result: String?,
        error: String?
    ) {
        tasks[id]?.let {
            tasks[id] = it.copy(
                status = status,
                completedAt = completedAt,
                result = result,
                error = error
            )
        }
    }

    override suspend fun deleteById(id: String) {
        tasks.remove(id)
    }
}
