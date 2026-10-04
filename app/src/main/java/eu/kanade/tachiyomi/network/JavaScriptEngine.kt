// Copyright The Keiyoushi Contributors. Apache-2.0.
package eu.kanade.tachiyomi.network

import android.content.Context
import java.io.IOException

class JavaScriptEngine(context: Context) {

    // TODO(外部约束): 宿主 JS 引擎 (quickjs) 桥接未接入; 当前扩展 import 计数为 0, 先显式失败
    suspend fun <T> evaluate(script: String): T = throw IOException("JavaScriptEngine is not wired to the host JS engine")
}
