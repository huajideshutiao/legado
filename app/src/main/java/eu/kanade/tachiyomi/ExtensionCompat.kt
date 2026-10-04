// Copyright The Keiyoushi Contributors. Apache-2.0.
// 漫画扩展 (eu.kanade.tachiyomi.*) 兼容层的 Injekt 注册点; 在 App.onCreate 早期调用一次,
// 必须先于任何扩展类加载 (keiyoushi.utils 的顶层属性会在 <clinit> 里 Injekt.get)
package eu.kanade.tachiyomi

import android.app.Application
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory

fun registerExtensionCompat(application: Application) {
    Injekt.addSingleton(application)
    Injekt.addSingletonFactory<Json> {
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
    Injekt.addSingletonFactory { NetworkHelper(application) }
}
