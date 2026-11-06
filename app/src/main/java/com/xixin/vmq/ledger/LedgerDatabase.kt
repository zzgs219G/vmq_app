package com.xixin.vmq.ledger

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 本地账本数据库。
 *
 * 不使用 fallbackToDestructiveMigration —— 那会在版本升级时清空整张表。
 * 账本是长期积累的数据，每次结构变更都必须写正式 Migration。
 */
@Database(
    entities = [Txn::class],
    version = 2,
    exportSchema = false
)
abstract class LedgerDatabase : RoomDatabase() {

    abstract fun txnDao(): TxnDao

    companion object {
        private const val DB_NAME = "vmq_ledger.db"

        /**
         * v1 → v2：新增 pushDismissed。
         *
         * 账本是长期数据，绝不使用 fallbackToDestructiveMigration ——
         * 那会在结构升级时清空整张表，等于把用户所有历史账目抹掉。
         * 新增列必须显式写 ALTER TABLE。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE txns ADD COLUMN pushDismissed INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        @Volatile
        private var instance: LedgerDatabase? = null

        fun get(context: Context): LedgerDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LedgerDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    // 后续版本在此追加 .addMigrations(...)
                    .build()
                    .also { instance = it }
            }
    }
}
