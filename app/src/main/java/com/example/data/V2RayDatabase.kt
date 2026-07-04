package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ServerEntity::class, SubscriptionEntity::class, LogEntity::class],
    version = 2,
    exportSchema = false
)
abstract class V2RayDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun logDao(): LogDao

    companion object {
        @Volatile
        private var INSTANCE: V2RayDatabase? = null

        fun getDatabase(context: Context): V2RayDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    V2RayDatabase::class.java,
                    "v2ray_dan_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
