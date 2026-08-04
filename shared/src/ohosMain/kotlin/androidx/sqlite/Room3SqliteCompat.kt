package androidx.sqlite

/**
 * room3-compiler 3.0.0-alpha01 (CPF 鸿蒙 fork 配套版本) 生成的 DAO 实现代码以
 * `import androidx.sqlite.prepare` / `import androidx.sqlite.step` 引用顶层扩展函数,
 * 而 CPF fork 的 sqlite (2.7.0-alpha01-0.3.0) 中 prepare/step 是 SQLiteConnection /
 * SQLiteStatement 的**成员函数**, 没有顶层扩展 → 生成代码的 import 无法解析,
 * 报 "Unresolved reference 'prepare'/'step'"。
 *
 * 本文件补齐这两个顶层扩展声明: Kotlin 解析调用点时成员函数优先于扩展函数,
 * 因此生成的 `_connection.prepare(sql)` / `_stmt.step()` 实际仍走 fork 的成员实现,
 * 扩展函数体只是透传兜底 (成员存在时不会被执行到)。
 *
 * 仅 ohosMain 源集可见 (iOS/Android/JVM 用 3.0.1 compiler, 生成代码走成员调用, 不需要此兼容)。
 */
@Suppress("unused", "FunctionName")
public fun SQLiteConnection.prepare(sql: String): SQLiteStatement = this.prepare(sql)

@Suppress("unused", "FunctionName")
public fun SQLiteStatement.step(): Boolean = this.step()
