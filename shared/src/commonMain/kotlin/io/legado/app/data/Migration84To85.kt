package io.legado.app.data

import androidx.room3.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import io.legado.app.data.entities.BookGroup

/**
 * 84→85 自动迁移的数据清洗 (原 app 端 `Migration84To85.onPostMigrate` 下沉)。
 *
 * 历史数据清洗原写在 AppDatabase.onOpen 里每次打开库都会跑, 实际只需对存量数据执行一次,
 * 借 84→85 自动迁移的 onPostMigrate 一次性完成。本对象集中放清洗 SQL (纯 SQL, 无平台差异),
 * 各平台 [Migration84To85] actual 的 onPostMigrate 只做一行委托。
 */
object Migration84To85Data {

    /**
     * 与原版 onPostMigrate 逐条对应:
     * 1. 移除已废弃的分组: 音频(-3)、本地未分组(-5)
     * 2. 网络未分组(-4 = [BookGroup.IdUngrouped]) 统一重命名为未分组
     * 3. 旧版误把字符串 'null' 当作 loginUi 写入 (book_sources / httpTTS), 置空
     * 4. httpTTS.concurrentRate 空值补 '0'
     */
    fun clean(connection: SQLiteConnection) {
        // 移除已废弃的分组: 音频(-3)、本地未分组(-5)
        connection.execSQL("delete from book_groups where groupId in (-3, -5)")
        // 网络未分组(-4)统一重命名为未分组
        connection.execSQL(
            "update book_groups set groupName = '未分组' " +
                "where groupId = ${BookGroup.IdUngrouped} " +
                "and groupName = '网络未分组'"
        )
        // 旧版误把字符串 'null' 当作 loginUi 写入
        connection.execSQL("update book_sources set loginUi = null where loginUi = 'null'")
        connection.execSQL("update httpTTS set loginUi = null where loginUi = 'null'")
        connection.execSQL("update httpTTS set concurrentRate = '0' where concurrentRate is null")
    }
}

/**
 * 84→85 自动迁移的数据清洗 spec (AutoMigrationSpec, 挂在 @Database autoMigrations)。
 *
 * 官方 Room3 的 `AutoMigrationSpec.onPostMigrate` 是 suspend, 鸿蒙 CPF fork 的不是
 * (与 AppDatabaseDefaults 同一处平台差异), 故 expect/actual 按平台分文件:
 * - jvmAndAndroidMain / iosMain: suspend 版本
 * - ohosMain: 非 suspend 版本
 * 清洗逻辑见 [Migration84To85Data], 三个 actual 均只做委托。
 */
expect class Migration84To85 : AutoMigrationSpec
