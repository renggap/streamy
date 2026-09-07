package com.streamy.app.data.db

import android.content.Context
import androidx.room.Room

/** Single Room instance per process — replaces the three independent `streamy.db` opens. */
object DatabaseHolder {
    @Volatile private var instance: AppDatabase? = null

    fun get(context: Context): AppDatabase =
        instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "streamy.db",
            ).addMigrations(
                AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5,
            ).build().also { instance = it }
        }
}
