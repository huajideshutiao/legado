package io.legado.app.data

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import io.legado.app.data.entities.ReadRecord
import io.legado.app.utils.midnightSecFromDayKey
import io.legado.app.utils.prevDayKey
import io.legado.app.utils.systemCurrentTimeMillis

/**
 * 80→83 手写 Migration 数组 (原 app 端 `DatabaseMigrations` 下沉)。
 *
 * 调用方 (各平台 `Room.databaseBuilder(...).addMigrations(*DatabaseMigrations.migrations)`) 签名不变。
 */
object DatabaseMigrations {

    val migrations: Array<Migration> by lazy {
        arrayOf(
            migration80To81(), migration81To82(), migration82To83()
        )
    }

}

/**
 * 三条手写迁移的迁移体 (纯 SQL / 纯逻辑, 无平台差异)。
 *
 * 时间换算走 [midnightSecFromDayKey] / [prevDayKey] / [systemCurrentTimeMillis] expect
 * (JVM 半区 actual 就是原来的 java.util.Calendar 代码), 各平台 Migration 子类只做一行委托。
 */
object DatabaseMigrationsData {

    fun migrate80To81(connection: SQLiteConnection) {
        connection.execSQL("DROP TABLE IF EXISTS searchBooks")
    }

    fun migrate81To82(connection: SQLiteConnection) {
        // 旧 readRecord: (deviceId, bookName, readTime累计, lastRead毫秒)
        // 新 readRecord: (bookName, day yyyyMMdd, readTime增量, lastRead毫秒) PK(bookName, day)
        //
        // 迁移策略：按 bookName 聚合，把全部累计时长归到 dayKey(maxLastRead) 那一天。
        // 历史细分数据无法还原，至少保住总时长和"最后阅读日"。

        // 1. 读出聚合后的旧数据
        data class OldRow(val bookName: String, val readTime: Long, val lastRead: Long)

        val rows = mutableListOf<OldRow>()
        connection.prepare(
            "select bookName, sum(readTime), max(lastRead) from readRecord group by bookName"
        ).use { stmt ->
            while (stmt.step()) {
                rows.add(OldRow(stmt.getText(0), stmt.getLong(1), stmt.getLong(2)))
            }
        }

        // 2. 重建表（含 lastRead 列）
        connection.execSQL("DROP TABLE readRecord")
        connection.execSQL(
            """
                CREATE TABLE IF NOT EXISTS readRecord (
                    bookName TEXT NOT NULL,
                    day INTEGER NOT NULL,
                    readTime INTEGER NOT NULL DEFAULT 0,
                    lastRead INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(bookName, day)
                )
                """.trimIndent()
        )

        // 3. 写回
        val now = systemCurrentTimeMillis()
        connection.prepare(
            "INSERT OR REPLACE INTO readRecord(bookName, day, readTime, lastRead) VALUES(?, ?, ?, ?)"
        ).use { insert ->
            for (row in rows) {
                if (row.bookName.isEmpty() || row.readTime <= 0) continue
                val ms = if (row.lastRead > 0) row.lastRead else now
                val day = ReadRecord.dayKey(ms / 1000)
                insert.bindText(1, row.bookName)
                insert.bindInt(2, day)
                insert.bindLong(3, row.readTime)
                insert.bindLong(4, ms)
                insert.step()
                insert.reset()
            }
        }
    }

    fun migrate82To83(connection: SQLiteConnection) {
        data class OldRow(
            val bookName: String,
            val day: Int,
            val readTimeMs: Long,
            val lastReadMs: Long
        )

        val rows = mutableListOf<OldRow>()
        connection.prepare("select bookName, day, readTime, lastRead from readRecord").use { stmt ->
            while (stmt.step()) {
                rows.add(OldRow(stmt.getText(0), stmt.getInt(1), stmt.getLong(2), stmt.getLong(3)))
            }
        }

        connection.execSQL("DROP TABLE readRecord")
        connection.execSQL(
            """CREATE TABLE readRecord (
                    bookName TEXT NOT NULL,
                    day INTEGER NOT NULL,
                    startSec INTEGER NOT NULL,
                    endSec INTEGER NOT NULL,
                    PRIMARY KEY(bookName, day, startSec)
                )""".trimIndent()
        )

        val nowSec = systemCurrentTimeMillis() / 1000
        connection.prepare("INSERT OR IGNORE INTO readRecord VALUES(?,?,?,?)").use { insert ->
            for (row in rows) {
                if (row.bookName.isEmpty() || row.readTimeMs <= 0) continue
                var remaining = row.readTimeMs / 1000
                val endSec0 = if (row.lastReadMs > 0) row.lastReadMs / 1000 else nowSec
                var curDay = row.day
                val curEndSec = endSec0

                val dayStartSec = midnightSecFromDayKey(curDay)
                val maxBack = minOf(16L * 3600, (curEndSec - dayStartSec).coerceAtLeast(0))
                val seg0 = minOf(remaining, maxBack)
                if (seg0 > 0) {
                    insert.bindText(1, row.bookName)
                    insert.bindInt(2, curDay)
                    insert.bindLong(3, curEndSec - seg0)
                    insert.bindLong(4, curEndSec)
                    insert.step()
                    insert.reset()
                    remaining -= seg0
                }

                curDay = prevDayKey(curDay)
                while (remaining > 0) {
                    val winEnd = midnightSecFromDayKey(curDay) + 20L * 3600
                    val seg = minOf(remaining, 16L * 3600)
                    insert.bindText(1, row.bookName)
                    insert.bindInt(2, curDay)
                    insert.bindLong(3, winEnd - seg)
                    insert.bindLong(4, winEnd)
                    insert.step()
                    insert.reset()
                    remaining -= seg
                    curDay = prevDayKey(curDay)
                }
            }
        }
    }
}

/**
 * 三条 Migration 子类由各平台源集提供: 官方 Room3 的 `Migration.migrate` 是 suspend,
 * 鸿蒙 CPF fork 的不是 (与 [Migration84To85] 同一处平台差异)。
 *
 * 与 [Migration84To85] 的区别: `AutoMigrationSpec.onPostMigrate` 有默认实现, 故那边能用
 * `expect class ... : AutoMigrationSpec`; 而 `Migration.migrate` 是 abstract, expect class
 * 无法留一个签名随平台变的抽象成员不声明, 故这里用 expect 工厂函数, 三个 actual 只做一行委托。
 */
internal expect fun migration80To81(): Migration

internal expect fun migration81To82(): Migration

internal expect fun migration82To83(): Migration
