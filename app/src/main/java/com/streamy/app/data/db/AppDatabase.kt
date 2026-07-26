package com.streamy.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ProfileEntity::class], version = 4, exportSchema = false)
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

        /**
         * v2 → v3: add DJ-mode (sc_trans) fields. All nullable/defaulted so existing SOURCE
         * rows upgrade as SOURCE with no DJ credentials — nothing changes for current users.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN mode TEXT NOT NULL DEFAULT 'SOURCE'")
                db.execSQL("ALTER TABLE profiles ADD COLUMN djUser TEXT")
                db.execSQL("ALTER TABLE profiles ADD COLUMN djPort INTEGER")
            }
        }

        /**
         * v3 → v4: DJ sub-protocol + Icecast mount. djProtocol defaults AUTO; djMount nullable.
         * Existing DJ rows (ULTRAVOX/sc_trans) keep working under AUTO, which sniffs at connect.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN djProtocol TEXT NOT NULL DEFAULT 'AUTO'")
                db.execSQL("ALTER TABLE profiles ADD COLUMN djMount TEXT")
            }
        }
    }
}
