package com.streamy.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ProfileEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao

    companion object {
        /**
         * v1 → v2: add nullable admin.cgi credentials (adminUser, adminPassword) for
         * now-playing metadata. Both nullable so existing rows + the [IntegrationTest] seed
         * (which omits them) keep working without changes.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN adminUser TEXT")
                db.execSQL("ALTER TABLE profiles ADD COLUMN adminPassword TEXT")
            }
        }
    }
}
