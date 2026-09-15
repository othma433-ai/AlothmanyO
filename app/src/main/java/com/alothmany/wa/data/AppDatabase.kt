package com.alothmany.wa.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        JobEntity::class,
        GroupEntity::class,
        LinkEntity::class,
        JobLinkEntity::class,
        OccurrenceEntity::class,
        CheckpointEntity::class,
        DiagnosticEventEntity::class,
        ActionJobEntity::class,
        ActionItemEntity::class,
        ExtractionQueueItemEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun jobDao(): JobDao
    abstract fun groupDao(): GroupDao
    abstract fun linkDao(): LinkDao
    abstract fun jobLinkDao(): JobLinkDao
    abstract fun occurrenceDao(): OccurrenceDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun diagnosticDao(): DiagnosticDao
    abstract fun actionJobDao(): ActionJobDao
    abstract fun actionItemDao(): ActionItemDao
    abstract fun extractionQueueDao(): ExtractionQueueDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `action_jobs` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `targetPackage` TEXT NOT NULL, `status` TEXT NOT NULL, `payloadText` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `totalItems` INTEGER NOT NULL, `completedItems` INTEGER NOT NULL, `failedItems` INTEGER NOT NULL, `lastError` TEXT, PRIMARY KEY(`id`))"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_action_jobs_updatedAt` ON `action_jobs` (`updatedAt`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `action_items` (`jobId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `target` TEXT NOT NULL, `status` TEXT NOT NULL, `detail` TEXT, `attempts` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`jobId`, `itemId`))"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_action_items_jobId_status` ON `action_items` (`jobId`, `status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_action_items_updatedAt` ON `action_items` (`updatedAt`)")
            }
        }


        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `jobs` ADD COLUMN `lastProgressAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `action_jobs` ADD COLUMN `lastProgressAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `action_items` ADD COLUMN `phase` TEXT NOT NULL DEFAULT 'PREPARE'")
                db.execSQL("ALTER TABLE `action_items` ADD COLUMN `resultState` TEXT")
                db.execSQL("ALTER TABLE `action_items` ADD COLUMN `lastEvidence` TEXT")
                db.execSQL("ALTER TABLE `action_items` ADD COLUMN `verificationAttempts` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `extraction_queue_items` (`jobId` TEXT NOT NULL, `groupId` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `targetPackage` TEXT NOT NULL, `expectedTitle` TEXT NOT NULL, `expectedIdentitySignature` TEXT, `discoveryProvenance` TEXT NOT NULL, `newOnlyBoundaryAtStart` TEXT, `mode` TEXT NOT NULL, `status` TEXT NOT NULL, `retryCount` INTEGER NOT NULL, `lastState` TEXT NOT NULL, `checkpointId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`jobId`, `groupId`))"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_extraction_queue_items_jobId_status` ON `extraction_queue_items` (`jobId`, `status`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_extraction_queue_items_jobId_ordinal` ON `extraction_queue_items` (`jobId`, `ordinal`)")
            }
        }

        fun create(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "wa-al-othmany.db"
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
    }
}
