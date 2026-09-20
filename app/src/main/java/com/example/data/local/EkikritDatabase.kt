package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        StudentEntity::class,
        SchemeEntity::class,
        ApplicationEntity::class,
        DocumentEntity::class,
        VerificationRecordEntity::class,
        ReviewQueueEntity::class,
        DisbursementEntity::class,
        NotificationEntity::class,
        ApplicationDraftEntity::class,
        AuditLogEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class EkikritDatabase : RoomDatabase() {
    abstract fun studentDao(): StudentDao
    abstract fun schemeDao(): SchemeDao
    abstract fun applicationDao(): ApplicationDao
    abstract fun documentDao(): DocumentDao
    abstract fun verificationRecordDao(): VerificationRecordDao
    abstract fun reviewQueueDao(): ReviewQueueDao
    abstract fun disbursementDao(): DisbursementDao
    abstract fun notificationDao(): NotificationDao
    abstract fun applicationDraftDao(): ApplicationDraftDao
    abstract fun auditLogDao(): AuditLogDao

    companion object {
        @Volatile
        private var INSTANCE: EkikritDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE applications ADD COLUMN academicYear TEXT NOT NULL DEFAULT '2026-27'")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_applications_studentId_schemeId_academicYear ON applications(studentId, schemeId, academicYear)"
                )
                db.execSQL("ALTER TABLE audit_logs ADD COLUMN studentId TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE audit_logs SET studentId = 'STU_2026_01' WHERE studentId = ''")
            }
        }

        fun getDatabase(context: Context, scope: CoroutineScope): EkikritDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    EkikritDatabase::class.java,
                    "ekikrit_unified_db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            scope.launch(Dispatchers.IO) {
                                INSTANCE?.let { SeedData.populateInitialDatabase(it) }
                            }
                        }

                        override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                            super.onDestructiveMigration(db)
                            scope.launch(Dispatchers.IO) {
                                INSTANCE?.let { SeedData.populateInitialDatabase(it) }
                            }
                        }
                    })
                    .build()

                INSTANCE = instance
                // Ensure initial seed data exists on startup if database was newly created or cleared
                scope.launch(Dispatchers.IO) {
                    if (instance.studentDao().getStudent("STU_2026_01") == null) {
                        SeedData.populateInitialDatabase(instance)
                    }
                }
                instance
            }
        }
    }
}
