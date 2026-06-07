package io.legado.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.Calendar

object DatabaseMigrations {

    val migrations: Array<Migration> by lazy {
        arrayOf(
            migration_80_81, migration_81_82, migration_82_83, migration_84_85
        )
    }

    // 历史数据清洗：原本写在 AppDatabase.onOpen 里，每次打开库都会跑，
    // 实际只需对存量数据执行一次，改为一次性迁移。
    private val migration_84_85 = object : Migration(84, 85) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 移除已废弃的分组：音频(-3)、本地未分组(-5)
            db.execSQL("delete from book_groups where groupId in (-3, -5)")
            // 网络未分组(-4)统一重命名为未分组
            db.execSQL(
                "update book_groups set groupName = '未分组' " +
                    "where groupId = ${io.legado.app.data.entities.BookGroup.IdUngrouped} " +
                    "and groupName = '网络未分组'"
            )
            // 旧版误把字符串 'null' 当作 loginUi 写入
            db.execSQL("update book_sources set loginUi = null where loginUi = 'null'")
            db.execSQL("update httpTTS set loginUi = null where loginUi = 'null'")
            db.execSQL("update httpTTS set concurrentRate = '0' where concurrentRate is null")
        }
    }

    private val migration_80_81 = object : Migration(80, 81) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS searchBooks")
        }
    }

    private val migration_82_83 = object : Migration(82, 83) {
        override fun migrate(db: SupportSQLiteDatabase) {
            data class OldRow(
                val bookName: String,
                val day: Int,
                val readTimeMs: Long,
                val lastReadMs: Long
            )

            val rows = mutableListOf<OldRow>()
            db.query("select bookName, day, readTime, lastRead from readRecord").use { c ->
                while (c.moveToNext()) {
                    rows.add(OldRow(c.getString(0), c.getInt(1), c.getLong(2), c.getLong(3)))
                }
            }

            db.execSQL("DROP TABLE readRecord")
            db.execSQL(
                """CREATE TABLE readRecord (
                    bookName TEXT NOT NULL,
                    day INTEGER NOT NULL,
                    startSec INTEGER NOT NULL,
                    endSec INTEGER NOT NULL,
                    PRIMARY KEY(bookName, day, startSec)
                )""".trimIndent()
            )

            val nowSec = System.currentTimeMillis() / 1000
            for (row in rows) {
                if (row.bookName.isEmpty() || row.readTimeMs <= 0) continue
                var remaining = row.readTimeMs / 1000
                val endSec0 = if (row.lastReadMs > 0) row.lastReadMs / 1000 else nowSec
                var curDay = row.day
                var curEndSec = endSec0

                val dayStartSec = dayToMidnightSec(curDay)
                val maxBack = minOf(16L * 3600, (curEndSec - dayStartSec).coerceAtLeast(0))
                val seg0 = minOf(remaining, maxBack)
                if (seg0 > 0) {
                    db.execSQL(
                        "INSERT OR IGNORE INTO readRecord VALUES(?,?,?,?)",
                        arrayOf<Any>(row.bookName, curDay, curEndSec - seg0, curEndSec)
                    )
                    remaining -= seg0
                }

                curDay = prevDay(curDay)
                while (remaining > 0) {
                    val winEnd = dayToMidnightSec(curDay) + 20L * 3600
                    val seg = minOf(remaining, 16L * 3600)
                    db.execSQL(
                        "INSERT OR IGNORE INTO readRecord VALUES(?,?,?,?)",
                        arrayOf<Any>(row.bookName, curDay, winEnd - seg, winEnd)
                    )
                    remaining -= seg
                    curDay = prevDay(curDay)
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
        override fun migrate(db: SupportSQLiteDatabase) {
            // 旧 readRecord: (deviceId, bookName, readTime累计, lastRead毫秒)
            // 新 readRecord: (bookName, day yyyyMMdd, readTime增量, lastRead毫秒) PK(bookName, day)
            //
            // 迁移策略：按 bookName 聚合，把全部累计时长归到 dayKey(maxLastRead) 那一天。
            // 历史细分数据无法还原，至少保住总时长和"最后阅读日"。

            // 1. 读出聚合后的旧数据
            data class OldRow(val bookName: String, val readTime: Long, val lastRead: Long)

            val rows = mutableListOf<OldRow>()
            db.query(
                "select bookName, sum(readTime), max(lastRead) from readRecord group by bookName"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    rows.add(OldRow(cursor.getString(0), cursor.getLong(1), cursor.getLong(2)))
                }
            }

            // 2. 重建表（含 lastRead 列）
            db.execSQL("DROP TABLE readRecord")
            db.execSQL(
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
            for (row in rows) {
                if (row.bookName.isEmpty() || row.readTime <= 0) continue
                val ms = if (row.lastRead > 0) row.lastRead else now
                val day = io.legado.app.data.entities.ReadRecord.dayKey(ms / 1000)
                db.execSQL(
                    "INSERT OR REPLACE INTO readRecord(bookName, day, readTime, lastRead) VALUES(?, ?, ?, ?)",
                    arrayOf<Any>(row.bookName, day, row.readTime, ms)
                )
            }
        }
    }

}