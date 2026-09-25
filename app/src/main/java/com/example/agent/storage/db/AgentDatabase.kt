package com.example.agent.storage.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.agent.storage.dao.AgentConfigDao
import com.example.agent.storage.dao.AgentMessageDao
import com.example.agent.storage.dao.AgentSessionDao
import com.example.agent.storage.dao.AgentTaskDao
import com.example.agent.storage.dao.ApprovalRequestDao
import com.example.agent.storage.dao.ContactRuleDao
import com.example.agent.storage.dao.MemoryItemDao
import com.example.agent.storage.dao.ScheduledTaskDao
import com.example.agent.storage.entity.AgentConfigEntity
import com.example.agent.storage.entity.AgentMessageEntity
import com.example.agent.storage.entity.AgentSessionEntity
import com.example.agent.storage.entity.AgentTaskEntity
import com.example.agent.storage.entity.ApprovalRequestEntity
import com.example.agent.storage.entity.ContactRuleEntity
import com.example.agent.storage.entity.MemoryItemEntity
import com.example.agent.storage.entity.ScheduledTaskEntity

@Database(
    entities = [
        AgentSessionEntity::class,
        AgentMessageEntity::class,
        AgentConfigEntity::class,
        ContactRuleEntity::class,
        MemoryItemEntity::class,
        ApprovalRequestEntity::class,
        ScheduledTaskEntity::class,
        AgentTaskEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AgentDatabase : RoomDatabase() {

    abstract fun sessionDao(): AgentSessionDao
    abstract fun messageDao(): AgentMessageDao
    abstract fun configDao(): AgentConfigDao
    abstract fun contactRuleDao(): ContactRuleDao
    abstract fun memoryItemDao(): MemoryItemDao
    abstract fun approvalRequestDao(): ApprovalRequestDao
    abstract fun scheduledTaskDao(): ScheduledTaskDao
    abstract fun agentTaskDao(): AgentTaskDao

    companion object {
        @Volatile
        private var INSTANCE: AgentDatabase? = null

        /**
         * v1 -> v2: five new tables (contact rules, long-term memory, approvals,
         * scheduled tasks, subagent tasks) plus new columns on agent_configs. Every
         * added config column has a default so existing rows stay valid.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `contact_rules` (`id` TEXT NOT NULL, `contactId` TEXT NOT NULL, " +
                        "`mode` TEXT NOT NULL, `label` TEXT, `createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_contact_rules_contactId` ON `contact_rules` (`contactId`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memory_items` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                        "`content` TEXT NOT NULL, `keywords` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                        "`conversationId` TEXT, `source` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, `lastUsedAt` INTEGER NOT NULL, `useCount` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_items_type` ON `memory_items` (`type`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_items_status` ON `memory_items` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_items_conversationId` ON `memory_items` (`conversationId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_items_updatedAt` ON `memory_items` (`updatedAt`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `approval_requests` (`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, " +
                        "`toolName` TEXT NOT NULL, `arguments` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                        "`requestedAt` INTEGER NOT NULL, `resolvedAt` INTEGER, `channel` TEXT NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_approval_requests_status` ON `approval_requests` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_approval_requests_requestedAt` ON `approval_requests` (`requestedAt`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `scheduled_tasks` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`schedule` TEXT NOT NULL, `prompt` TEXT NOT NULL, `conversationId` TEXT NOT NULL, " +
                        "`agentId` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`lastRunAt` INTEGER, `nextRunAt` INTEGER, `lastStatus` TEXT, `lastResult` TEXT, " +
                        "`lastError` TEXT, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scheduled_tasks_enabled` ON `scheduled_tasks` (`enabled`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scheduled_tasks_nextRunAt` ON `scheduled_tasks` (`nextRunAt`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `agent_tasks` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`task` TEXT NOT NULL, `agentName` TEXT NOT NULL, `modelId` TEXT NOT NULL, " +
                        "`systemPrompt` TEXT NOT NULL, `toolsEnabled` INTEGER NOT NULL, " +
                        "`conversationId` TEXT NOT NULL, `channel` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `startedAt` INTEGER, `completedAt` INTEGER, " +
                        "`result` TEXT, `error` TEXT, `parentConversationId` TEXT NOT NULL, " +
                        "`scheduledTaskId` TEXT, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_tasks_status` ON `agent_tasks` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_tasks_conversationId` ON `agent_tasks` (`conversationId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_tasks_createdAt` ON `agent_tasks` (`createdAt`)")

                db.execSQL("ALTER TABLE `agent_configs` ADD COLUMN `whitelistMode` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `agent_configs` ADD COLUMN `longTermMemoryEnabled` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `agent_configs` ADD COLUMN `autoCompactEnabled` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `agent_configs` ADD COLUMN `maxContextMessages` INTEGER NOT NULL DEFAULT 30")
                db.execSQL(
                    "ALTER TABLE `agent_configs` ADD COLUMN `visionBaseUrl` TEXT NOT NULL " +
                        "DEFAULT 'https://generativelanguage.googleapis.com/v1beta/openai'"
                )
                db.execSQL("ALTER TABLE `agent_configs` ADD COLUMN `visionApiKey` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `agent_configs` ADD COLUMN `visionModelId` TEXT NOT NULL DEFAULT 'gemini-2.0-flash'")
                db.execSQL("ALTER TABLE `agent_configs` ADD COLUMN `agentName` TEXT NOT NULL DEFAULT 'Personal AI Agent'")
            }
        }

        fun getInstance(context: Context): AgentDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AgentDatabase::class.java,
                    "agent_database.db"
                )
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { INSTANCE = it }
            }
        }
    }
}
