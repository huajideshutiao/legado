// android.app.Application JVM stub: 供扩展 dex 链接 Application 引用 (Injekt 注入面), 行为全走 Context stub。
// 继承 ContextWrapper: 扩展 dex 的 getSharedPreferences 调用被 R8 落到 ContextWrapper 方法引用面。
package android.app

import android.content.ContextWrapper

open class Application : ContextWrapper()
