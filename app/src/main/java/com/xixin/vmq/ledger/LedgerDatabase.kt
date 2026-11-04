package com.xixin.vmq.ledger

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * 本地账本数据库。
 *
 * ## 为什么不用 fallbackToDestructiveMigration
 *
 * 那会在版本升级时**清空整张表**。账本是长期积累的数据，
 * 一次升级全丢对用户是灾难性的。每一次结构变更都必须写正式 Migration。
 *
 * ## 当前版本
 *
 * v1：初始结构（Txn）。尚无迁移需求。
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
                    // 后续版本在此追加 .addMigrations(MIGRATION_1_2, ...)
                    .build()
                    .also { instance = it }
            }
    }
}
