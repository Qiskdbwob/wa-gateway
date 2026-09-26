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
import com.example.agent.storage.dao.McpServerDao
import com.example.agent.storage.dao.MemoryItemDao
import com.example.agent.storage.dao.ScheduledTaskDao
import com.example.agent.storage.entity.AgentConfigEntity
import com.example.agent.storage.entity.AgentMessageEntity
import com.example.agent.storage.entity.AgentSessionEntity
import com.example.agent.storage.entity.AgentTaskEntity
import com.example.agent.storage.entity.ApprovalRequestEntity
import com.example.agent.storage.entity.ContactRuleEntity
import com.example.agent.storage.entity.McpServerEntity
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
        AgentTaskEntity::class,
        McpServerEntity::class
    ],
    version = 5,
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
    abstract fun mcpServerDao(): McpServerDao

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

        /**
         * v2 -> v3: terminal + browser automation settings. All columns carry defaults so
         * existing installations keep working (browser stays off until the user enables it).
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `agent_configs` ADD COLUMN `terminalEnabled` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `agent_configs` ADD COLUMN `browserEnabled` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `agent_configs` ADD COLUMN `browserSites` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `agent_configs` ADD COLUMN `browserUserAgent` TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v3 -> v4: keys pool (extra API keys rotated on rate limit/quota). The column is a
         * blank encrypted blob by default, i.e. single-key behaviour is unchanged.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `agent_configs` ADD COLUMN `apiKeys` TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v4 -> v5: periodic self-reflection settings plus the MCP server table. Both default to
         * "nothing configured", so an existing install keeps working untouched.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `agent_configs` ADD COLUMN `autoReflectEnabled` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `agent_configs` ADD COLUMN `autoReflectIntervalHours` INTEGER NOT NULL DEFAULT 6")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `mcp_servers` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`url` TEXT NOT NULL, `headers` TEXT NOT NULL, `enabled` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_mcp_servers_name` ON `mcp_servers` (`name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_mcp_servers_enabled` ON `mcp_servers` (`enabled`)")
            }
        }

        fun getInstance(context: Context): AgentDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AgentDatabase::class.java,
                    "agent_database.db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
                .also { INSTANCE = it }
            }
        }
    }
}
