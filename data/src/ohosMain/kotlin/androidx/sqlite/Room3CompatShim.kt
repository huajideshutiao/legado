// CPF 鸿蒙 room3 fork 兼容 shim (只编入 ohosArm64)。
//
// 背景: 官方 androidx.sqlite 2.7.0 / CPF fork 2.7.0-alpha01-0.3.0 的 prepare/step 都是
// SQLiteConnection / SQLiteStatement 的接口成员方法; 但 room3-compiler 3.0.0-alpha01
// (kspOhosArm64 用的版本) 生成的 DAO 代码按旧 API 面生成 `import androidx.sqlite.prepare`
// / `import androidx.sqlite.step` 顶层函数引用。jvm/android 生成物不 import (用 3.0.1
// 编译器, 成员方法直调), 只有 ohosArm64 生成物需要这两个顶层声明。
//
// Kotlin 规则: 同接收者类型的扩展函数与成员方法可共存, 调用处成员优先 —— 这里的顶层
// 扩展仅用于满足 KSP 生成代码的 import 解析, 实际 `_connection.prepare(_sql)` /
// `_stmt.step()` 调用仍走接口成员方法, 行为零 diff。iOS 端 (iosMain) 用官方 3.0.1
// 编译器不生成该 import, 无需 shim。
package androidx.sqlite

/** 兼容 shim: 满足 room3-compiler alpha01 生成代码的 import (实际调用走成员方法)。 */
@Suppress("unused", "EXTENSION_SHADOWED_BY_MEMBER")
fun SQLiteConnection.prepare(sql: String): SQLiteStatement = this.prepare(sql)

/** 兼容 shim: 满足 room3-compiler alpha01 生成代码的 import (实际调用走成员方法)。 */
@Suppress("unused", "EXTENSION_SHADOWED_BY_MEMBER")
fun SQLiteStatement.step(): Boolean = this.step()
