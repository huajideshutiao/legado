package androidx.sqlite

/**
 * room3-compiler 3.0.0-alpha01 (CPF 鸿蒙 fork 配套版本) 生成的 DAO 实现代码以
 * `import androidx.sqlite.prepare` / `import androidx.sqlite.step` 引用顶层扩展函数,
 * 而 CPF fork 的 sqlite (2.7.0-alpha01-0.3.0) 中 prepare/step 是 SQLiteConnection /
 * SQLiteStatement 的**成员函数**, 没有顶层扩展 → 生成代码的 import 无法解析,
 * 报 "Unresolved reference 'prepare'/'step'"。
 *
 * 本文件补齐这两个顶层扩展声明: 生成代码的 `import androidx.sqlite.prepare` / `import
 * androidx.sqlite.step` 必须有对应声明才能解析; 调用点仍走 fork 的成员实现 (Kotlin 解析
 * 成员优先于扩展), 扩展体不会被执行。
 *
 * 仅 ohosMain 源集可见 (iOS/Android/JVM 用 3.0.1 compiler, 生成代码走成员调用, 不需要此兼容)。
 */
@Suppress("unused", "FunctionName", "EXTENSION_SHADOWED_BY_MEMBER")
public fun SQLiteConnection.prepare(sql: String): SQLiteStatement = this.prepare(sql)

@Suppress("unused", "FunctionName", "EXTENSION_SHADOWED_BY_MEMBER")
public fun SQLiteStatement.step(): Boolean = this.step()
