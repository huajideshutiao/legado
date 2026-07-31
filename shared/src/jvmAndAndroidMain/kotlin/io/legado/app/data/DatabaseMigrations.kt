package io.legado.app.data

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import java.util.Calendar

object DatabaseMigrations {

    val migrations: Array<Migration> by lazy {
        arrayOf(
            migration_80_81, migration_81_82, migration_82_83
        )
    }

    private val migration_80_81 = object : Migration(80, 81) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("DROP TABLE IF EXISTS searchBooks")
        }
    }

    private val migration_82_83 = object : Migration(82, 83) {
        override suspend fun migrate(connection: SQLiteConnection) {
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

            val nowSec = System.currentTimeMillis() / 1000
            connection.prepare("INSERT OR IGNORE INTO readRecord VALUES(?,?,?,?)").use { insert ->
                for (row in rows) {
                    if (row.bookName.isEmpty() || row.readTimeMs <= 0) continue
                    var remaining = row.readTimeMs / 1000
                    val endSec0 = if (row.lastReadMs > 0) row.lastReadMs / 1000 else nowSec
                    var curDay = row.day
                    val curEndSec = endSec0

                    val dayStartSec = dayToMidnightSec(curDay)
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

                    curDay = prevDay(curDay)
                    while (remaining > 0) {
                        val winEnd = dayToMidnightSec(curDay) + 20L * 3600
                        val seg = minOf(remaining, 16L * 3600)
                        insert.bindText(1, row.bookName)
                        insert.bindInt(2, curDay)
                        insert.bindLong(3, winEnd - seg)
                        insert.bindLong(4, winEnd)
                        insert.step()
                        insert.reset()
                        remaining -= seg
                        curDay = prevDay(curDay)
                    }
                }
            }
        }

        private fun dayToMidnightSec(day: Int): Long {
            val cal = Calendar.getInstance()
            cal.clear()
            cal.set(day / 10000, (day / 100) % 100 - 1, day % 100)
            return cal.timeInMillis / 1000
        }

        private fun prevDay(day: Int): Int {
            val cal = Calendar.getInstance()
            cal.clear()
            cal.set(day / 10000, (day / 100) % 100 - 1, day % 100)
            cal.add(Calendar.DAY_OF_MONTH, -1)
            return cal.get(Calendar.YEAR) * 10000 + (cal.get(Calendar.MONTH) + 1) * 100 + cal.get(
                Calendar.DAY_OF_MONTH
            )
        }
    }

    private val migration_81_82 = object : Migration(81, 82) {
        override suspend fun migrate(connection: SQLiteConnection) {
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
            val now = System.currentTimeMillis()
            connection.prepare(
                "INSERT OR REPLACE INTO readRecord(bookName, day, readTime, lastRead) VALUES(?, ?, ?, ?)"
            ).use { insert ->
                for (row in rows) {
                    if (row.bookName.isEmpty() || row.readTime <= 0) continue
                    val ms = if (row.lastRead > 0) row.lastRead else now
                    val day = io.legado.app.data.entities.ReadRecord.dayKey(ms / 1000)
                    insert.bindText(1, row.bookName)
                    insert.bindInt(2, day)
                    insert.bindLong(3, row.readTime)
                    insert.bindLong(4, ms)
                    insert.step()
                    insert.reset()
                }
            }
        }
    }

}

// K5-c Phase 5: Migration84To85 已随 @Database 下沉 shared/commonMain (autoMigrations spec 引用)
