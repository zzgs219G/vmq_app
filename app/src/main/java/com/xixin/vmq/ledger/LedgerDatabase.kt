package com.xixin.vmq.ledger

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 本地账本数据库。
 *
 * 不使用 fallbackToDestructiveMigration —— 那会在版本升级时清空整张表。
 * 账本是长期积累的数据，每次结构变更都必须写正式 Migration。
 */
@Database(
    entities = [Txn::class],
    version = 1,
    exportSchema = false
)
abstract class LedgerDatabase : RoomDatabase() {

    abstract fun txnDao(): TxnDao

    companion object {
        private const val DB_NAME = "vmq_ledger.db"

        @Volatile
        private var instance: LedgerDatabase? = null

        fun get(context: Context): LedgerDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LedgerDatabase::class.java,
                    DB_NAME
                )
                    // 后续版本在此追加 .addMigrations(...)
                    .build()
                    .also { instance = it }
            }
    }
}
