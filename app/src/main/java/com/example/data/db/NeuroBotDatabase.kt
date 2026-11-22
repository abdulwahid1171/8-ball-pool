package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TrainingProfileEntity::class,
        QTableEntryEntity::class,
        ExperienceEntity::class,
        TrainingSessionEntity::class,
        ShotPhysicsEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class NeuroBotDatabase : RoomDatabase() {
    abstract fun neuroBotDao(): NeuroBotDao

    companion object {
        @Volatile
        private var INSTANCE: NeuroBotDatabase? = null

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE training_profiles ADD COLUMN botSeatSide TEXT NOT NULL DEFAULT 'LEFT'")
                db.execSQL("ALTER TABLE training_profiles ADD COLUMN preferredSuit TEXT NOT NULL DEFAULT 'AUTO'")
                db.execSQL("ALTER TABLE training_profiles ADD COLUMN watchHumanLearn INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE training_profiles ADD COLUMN humanShotsObserved INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): NeuroBotDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NeuroBotDatabase::class.java,
                    "neurobot_memory.db"
                )
                    .addMigrations(MIGRATION_3_4)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
