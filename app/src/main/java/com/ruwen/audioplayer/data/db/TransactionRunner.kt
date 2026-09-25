package com.ruwen.audioplayer.data.db

import androidx.room.withTransaction

/**
 * 事务执行器：把「多步写库」包成一个原子操作。
 *
 * 之前 `addAudioItems`（插多行 + 改播放列表时间戳 + 回写列表封面）、
 * `removeAudioByFilePath`（删行 + 统计孤儿文件）这类操作都是分步执行、没有事务，
 * 中途失败会留下半成品（例如列表封面指向已删文件、条目插了一半）。
 *
 * 抽象成接口而不是直接用 `AppDatabase.withTransaction`：数据层不必持有 Room 类型，
 * 单测里可以塞一个直接执行 block 的实现。
 */
interface TransactionRunner {
    suspend fun <T> runInTransaction(block: suspend () -> T): T
}

/** 生产实现：走 Room 的 `withTransaction`（room-ktx 提供，内部用同一连接 + 事务） */
class RoomTransactionRunner(private val database: AppDatabase) : TransactionRunner {
    override suspend fun <T> runInTransaction(block: suspend () -> T): T =
        database.withTransaction { block() }
}
