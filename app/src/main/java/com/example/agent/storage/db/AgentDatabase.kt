package com.example.agent.storage.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.agent.storage.dao.AgentConfigDao
import com.example.agent.storage.dao.AgentMessageDao
import com.example.agent.storage.dao.AgentSessionDao
import com.example.agent.storage.entity.AgentConfigEntity
import com.example.agent.storage.entity.AgentMessageEntity
import com.example.agent.storage.entity.AgentSessionEntity

@Database(
    entities = [
        AgentSessionEntity::class,
        AgentMessageEntity::class,
        AgentConfigEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AgentDatabase : RoomDatabase() {

    abstract fun sessionDao(): AgentSessionDao
    abstract fun messageDao(): AgentMessageDao
    abstract fun configDao(): AgentConfigDao

    companion object {
        @Volatile
        private var INSTANCE: AgentDatabase? = null

        fun getInstance(context: Context): AgentDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AgentDatabase::class.java,
                    "agent_database.db"
                )
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
            }
        }
    }
}
