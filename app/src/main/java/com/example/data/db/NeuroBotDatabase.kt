package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TrainingProfileEntity::class,
        QTableEntryEntity::class,
        ExperienceEntity::class,
        TrainingSessionEntity::class,
        ShotPhysicsEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class NeuroBotDatabase : RoomDatabase() {
    abstract fun neuroBotDao(): NeuroBotDao

    companion object {
        @Volatile
        private var INSTANCE: NeuroBotDatabase? = null

        fun getInstance(context: Context): NeuroBotDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NeuroBotDatabase::class.java,
                    "neurobot_memory.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
