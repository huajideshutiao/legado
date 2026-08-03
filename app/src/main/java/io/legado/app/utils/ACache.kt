//Copyright (c) 2017. 章钦豪. All rights reserved.
package io.legado.app.utils

import splitties.init.appCtx
import java.io.File

/**
 * 本地缓存 (app 端 Android 专属层)。
 *
 * 继承 shared [ACacheBase] (纯 JDK: ACacheManager + Utils date info + String/ByteArray 读写),
 * 本类仅保留 Android 平台专属重载:
 * - myPid() 进程隔离后缀 (依赖 android.os.Process)
 *
 * cacheDir/filesDir 经 [ACacheDirProvider] 注入 (App.onCreate 注册), 不直接依赖 appCtx。
 */
class ACache private constructor(
    cacheDir: File,
    maxSize: Long,
    maxCount: Int
) : ACacheBase(cacheDir, maxSize, maxCount) {

    companion object {
        private val mInstanceMap = HashMap<String, ACache>()

        @JvmOverloads
        fun get(
            cacheName: String = "ACache",
            maxSize: Long = MAX_SIZE.toLong(),
            maxCount: Int = MAX_COUNT,
            cacheDir: Boolean = true
        ): ACache {
            val provider = ACacheProviders.get()
            val f =
                if (cacheDir) provider.getCacheDir(cacheName) else provider.getFilesDir(cacheName)
            return get(f, maxSize, maxCount)
        }

        @JvmOverloads
        fun get(
            cacheDir: File,
            maxSize: Long = MAX_SIZE.toLong(),
            maxCount: Int = MAX_COUNT
        ): ACache {
            synchronized(this) {
                var manager = mInstanceMap[cacheDir.absoluteFile.toString() + myPid()]
                if (manager == null) {
                    manager = ACache(cacheDir, maxSize, maxCount)
                    mInstanceMap[cacheDir.absolutePath + myPid()] = manager
                }
                return manager
            }
        }

        private fun myPid(): String {
            return "_" + android.os.Process.myPid()
        }
    }

}

/**
 * 注册 app 端 [ACacheDirProvider] 到 shared [ACacheProviders]。
 *
 * 调用时机: App.onCreate 中, 任何 ACache.get() 调用之前
 * (必须在 [registerAndroidFileCacheProvider] 之前, 因后者注册的 FileCacheProvider 委托 ACache)。
 *
 * 模式参考 [io.legado.app.help.file.registerAndroidAppFilesDir]。
 */
fun registerAndroidACacheDirProvider() {
    ACacheProviders.register(object : ACacheDirProvider {
        override fun getCacheDir(cacheName: String): File =
            File(appCtx.cacheDir, cacheName)

        override fun getFilesDir(cacheName: String): File =
            File(appCtx.filesDir, cacheName)
    })
}
