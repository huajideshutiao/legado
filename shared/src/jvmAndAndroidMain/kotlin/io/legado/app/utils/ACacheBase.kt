//Copyright (c) 2017. 章钦豪. All rights reserved.
package io.legado.app.utils

import io.legado.app.constant.AppLog
import io.legado.app.help.coroutine.printStackTraceOnDebug
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * ACache 的纯 JDK 部分下沉 (shared jvmAndAndroidMain)。
 *
 * # 背景
 * 原 app 端 [ACache] 基于 File + LRU 的本地缓存, 主体为 [ACacheManager] (文件 + LRU 管理) +
 * Utils 中的 date info 编解码 + String/ByteArray 读写方法, 均为纯 JDK 逻辑。
 *
 * # 下沉范围
 * - [ACacheManager]: 纯 java.io.File + AtomicLong/AtomicInteger + Collections.synchronizedMap
 * - [Utils] date info 编解码 (isDue / newStringWithDateInfo / newByteArrayWithDateInfo /
 *   clearDateInfo / hasDateInfo / getDateInfoFromDate / createDateInfo / copyOfRange / indexOf)
 * - String/ByteArray put/get 方法: 纯 JDK 文件读写
 *
 * # 保留在 app 端 (ACache.kt)
 * - Bitmap/Drawable 重载 (依赖 android.graphics.Bitmap/Drawable)
 * - JSONObject/JSONArray 重载 (依赖 org.json, Android 平台特有, 参照 ServerExt.kt 模式)
 * - myPid() 进程隔离后缀 (依赖 android.os.Process)
 *
 * # 目录注入
 * 通过 [ACacheDirProvider] 注入 cacheDir/filesDir, 解除对 appCtx 的直接依赖
 * (模式参考 [io.legado.app.help.file.AppFilesDirs])。
 */
interface ACacheDirProvider {

    /** 返回 cacheDir 下的子目录 File (对应 Android `File(appCtx.cacheDir, cacheName)`)。 */
    fun getCacheDir(cacheName: String): File

    /** 返回 filesDir 下的子目录 File (对应 Android `File(appCtx.filesDir, cacheName)`)。 */
    fun getFilesDir(cacheName: String): File
}

/**
 * [ACacheDirProvider] 容器 (provider 注入模式)。
 *
 * 宿主启动早期注册一次 (App.onCreate / desktop main), shared 内通过 [get] 获取。
 * 未注册时调用 [get] 抛 [IllegalStateException]。
 *
 * 模式参考 [io.legado.app.help.file.AppFilesDirs]。
 */
object ACacheProviders {

    @Volatile
    private var impl: ACacheDirProvider? = null

    /** 宿主启动早期注册一次 (任何 ACache.get() 之前)。 */
    fun register(provider: ACacheDirProvider) {
        impl = provider
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): ACacheDirProvider =
        impl ?: error("ACacheProviders not registered; call registerAndroidACacheDirProvider() first")

    /** 仅测试场景: 清空注册 (生产代码勿调用)。 */
    fun reset() {
        impl = null
    }
}

/**
 * ACache 的纯 JDK 基类 (shared jvmAndAndroidMain)。
 *
 * app 端 [ACache] 继承本类, 添加 Bitmap/Drawable/JSONObject/JSONArray 重载 + myPid() 后缀。
 *
 * @param cacheDir 缓存目录
 * @param maxSize 最大缓存字节数
 * @param maxCount 最大缓存文件数
 */
open class ACacheBase protected constructor(
    cacheDir: File,
    maxSize: Long,
    maxCount: Int
) {

    companion object {
        const val TIME_HOUR = 60 * 60
        const val TIME_DAY = TIME_HOUR * 24
        const val MAX_SIZE = 1000 * 1000 * 50 // 50 mb
        const val MAX_COUNT = Integer.MAX_VALUE // 不限制存放数据的数量
    }

    protected var mCache: ACacheManager? = null

    init {
        try {
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                AppLog.put("can't make dirs in " + cacheDir.absolutePath)
            }
            mCache = ACacheManager(cacheDir, maxSize, maxCount)
        } catch (e: Exception) {
            e.printStackTraceOnDebug()
        }
    }

    // =======================================
    // ============ String数据 读写 ==============
    // =======================================

    /**
     * 保存 String数据 到 缓存中
     *
     * @param key   保存的key
     * @param value 保存的String数据
     */
    fun put(key: String, value: String) {
        mCache?.let { mCache ->
            try {
                val file = mCache.newFile(key)
                file.writeText(value)
                mCache.put(file)
            } catch (e: Exception) {
                e.printStackTraceOnDebug()
            }
        }
    }

    /**
     * 保存 String数据 到 缓存中
     *
     * @param key      保存的key
     * @param value    保存的String数据
     * @param saveTime 保存的时间，单位：秒
     */
    fun put(key: String, value: String, saveTime: Int) {
        if (saveTime == 0) put(key, value) else put(
            key,
            Utils.newStringWithDateInfo(saveTime, value)
        )
    }

    /**
     * 读取 String数据
     *
     * @return String 数据
     */
    fun getAsString(key: String): String? {
        mCache?.let { mCache ->
            val file = mCache[key]
            if (!file.exists())
                return null
            var removeFile = false
            try {
                val text = file.readText()
                if (!Utils.isDue(text)) {
                    return Utils.clearDateInfo(text)
                } else {
                    removeFile = true
                }
            } catch (e: IOException) {
                e.printStackTraceOnDebug()
            } finally {
                if (removeFile)
                    remove(key)
            }
        }
        return null
    }

    // =======================================
    // ============== byte 数据 读写 =============
    // =======================================

    /**
     * 保存 byte数据 到 缓存中
     *
     * @param key   保存的key
     * @param value 保存的数据
     */
    fun put(key: String, value: ByteArray) {
        mCache?.let { mCache ->
            val file = mCache.newFile(key)
            file.writeBytes(value)
            mCache.put(file)
        }
    }

    /**
     * 保存 byte数据 到 缓存中
     *
     * @param key      保存的key
     * @param value    保存的数据
     * @param saveTime 保存的时间，单位：秒
     */
    fun put(key: String, value: ByteArray, saveTime: Int) {
        if (saveTime == 0) put(key, value)
        else put(key, Utils.newByteArrayWithDateInfo(saveTime, value))
    }

    /**
     * 获取 byte 数据
     *
     * @return byte 数据
     */
    fun getAsBinary(key: String): ByteArray? {
        mCache?.let { mCache ->
            var removeFile = false
            try {
                val file = mCache[key]
                if (!file.exists())
                    return null

                val byteArray = file.readBytes()
                return if (!Utils.isDue(byteArray)) {
                    Utils.clearDateInfo(byteArray)
                } else {
                    removeFile = true
                    null
                }
            } catch (e: Exception) {
                e.printStackTraceOnDebug()
            } finally {
                if (removeFile)
                    remove(key)
            }
        }
        return null
    }

    /**
     * 获取缓存文件
     *
     * @return value 缓存的文件
     */
    fun file(key: String): File? {
        mCache?.let { mCache ->
            try {
                val f = mCache.newFile(key)
                if (f.exists()) {
                    return f
                }
            } catch (e: Exception) {
                e.printStackTraceOnDebug()
            }
        }
        return null
    }

    /**
     * 移除某个key
     *
     * @return 是否移除成功
     */
    fun remove(key: String): Boolean {
        return mCache?.remove(key) == true
    }

    /**
     * 清除所有数据
     */
    fun clear() {
        mCache?.clear()
    }

    /**
     * @author 杨福海（michael） www.yangfuhai.com
     * @version 1.0
     * title 时间计算工具类
     */
    private object Utils {

        @Suppress("ConstPropertyName")
        private const val mSeparator = ' '

        /**
         * 判断缓存的String数据是否到期
         *
         * @return true：到期了 false：还没有到期
         */
        fun isDue(str: String): Boolean {
            return isDue(str.toByteArray())
        }

        /**
         * 判断缓存的byte数据是否到期
         *
         * @return true：到期了 false：还没有到期
         */
        fun isDue(data: ByteArray): Boolean {
            try {
                val text = getDateInfoFromDate(data)
                if (text != null && text.size == 2) {
                    var saveTimeStr = text[0]
                    while (saveTimeStr.startsWith("0")) {
                        saveTimeStr = saveTimeStr
                            .substring(1)
                    }
                    val saveTime = java.lang.Long.valueOf(saveTimeStr)
                    val deleteAfter = java.lang.Long.valueOf(text[1])
                    if (System.currentTimeMillis() > saveTime + deleteAfter * 1000) {
                        return true
                    }
                }
            } catch (e: Exception) {
                e.printStackTraceOnDebug()
            }

            return false
        }

        fun newStringWithDateInfo(second: Int, strInfo: String): String {
            return createDateInfo(second) + strInfo
        }

        fun newByteArrayWithDateInfo(second: Int, data2: ByteArray): ByteArray {
            val data1 = createDateInfo(second).toByteArray()
            val retData = ByteArray(data1.size + data2.size)
            System.arraycopy(data1, 0, retData, 0, data1.size)
            System.arraycopy(data2, 0, retData, data1.size, data2.size)
            return retData
        }

        fun clearDateInfo(strInfo: String?): String? {
            strInfo?.let {
                if (hasDateInfo(strInfo.toByteArray())) {
                    return strInfo.substring(strInfo.indexOf(mSeparator) + 1)
                }
            }
            return strInfo
        }

        fun clearDateInfo(data: ByteArray): ByteArray {
            return if (hasDateInfo(data)) {
                copyOfRange(
                    data, indexOf(data, mSeparator) + 1,
                    data.size
                )
            } else data
        }

        fun hasDateInfo(data: ByteArray?): Boolean {
            return (data != null && data.size > 15 && data[13] == '-'.code.toByte()
                    && indexOf(data, mSeparator) > 14)
        }

        fun getDateInfoFromDate(data: ByteArray): Array<String>? {
            if (hasDateInfo(data)) {
                val saveDate = String(copyOfRange(data, 0, 13))
                val deleteAfter = String(
                    copyOfRange(
                        data, 14,
                        indexOf(data, mSeparator)
                    )
                )
                return arrayOf(saveDate, deleteAfter)
            }
            return null
        }

        @Suppress("SameParameterValue")
        private fun indexOf(data: ByteArray, c: Char): Int {
            for (i in data.indices) {
                if (data[i] == c.code.toByte()) {
                    return i
                }
            }
            return -1
        }

        private fun copyOfRange(original: ByteArray, from: Int, to: Int): ByteArray {
            val newLength = to - from
            require(newLength >= 0) { "$from > $to" }
            val copy = ByteArray(newLength)
            System.arraycopy(
                original, from, copy, 0,
                min(original.size - from, newLength)
            )
            return copy
        }

        private fun createDateInfo(second: Int): String {
            val currentTime = StringBuilder(System.currentTimeMillis().toString() + "")
            while (currentTime.length < 13) {
                currentTime.insert(0, "0")
            }
            return "$currentTime-$second$mSeparator"
        }
    }

    /**
     * @author 杨福海（michael） www.yangfuhai.com
     * @version 1.0
     * title 缓存管理器
     */
    open class ACacheManager(
        private var cacheDir: File,
        private val sizeLimit: Long,
        private val countLimit: Int
    ) {
        private val cacheSize: AtomicLong = AtomicLong()
        private val cacheCount: AtomicInteger = AtomicInteger()
        private val lastUsageDates = Collections
            .synchronizedMap(HashMap<File, Long>())

        init {
            calculateCacheSizeAndCacheCount()
        }

        /**
         * 计算 cacheSize和cacheCount
         */
        private fun calculateCacheSizeAndCacheCount() {
            Thread {

                try {
                    var size = 0
                    var count = 0
                    val cachedFiles = cacheDir.listFiles()
                    if (cachedFiles != null) {
                        for (cachedFile in cachedFiles) {
                            size += calculateSize(cachedFile).toInt()
                            count += 1
                            lastUsageDates[cachedFile] = cachedFile.lastModified()
                        }
                        cacheSize.set(size.toLong())
                        cacheCount.set(count)
                    }
                } catch (e: Exception) {
                    e.printStackTraceOnDebug()
                }


            }.start()
        }

        fun put(file: File) {

            try {
                var curCacheCount = cacheCount.get()
                while (curCacheCount + 1 > countLimit) {
                    val freedSize = removeNext()
                    cacheSize.addAndGet(-freedSize)

                    curCacheCount = cacheCount.addAndGet(-1)
                }
                cacheCount.addAndGet(1)

                val valueSize = calculateSize(file)
                var curCacheSize = cacheSize.get()
                while (curCacheSize + valueSize > sizeLimit) {
                    val freedSize = removeNext()
                    curCacheSize = cacheSize.addAndGet(-freedSize)
                }
                cacheSize.addAndGet(valueSize)

                val currentTime = System.currentTimeMillis()
                file.setLastModified(currentTime)
                lastUsageDates[file] = currentTime
            } catch (e: Exception) {
                e.printStackTraceOnDebug()
            }

        }

        operator fun get(key: String): File {
            val file = newFile(key)
            val currentTime = System.currentTimeMillis()
            file.setLastModified(currentTime)
            lastUsageDates[file] = currentTime

            return file
        }

        fun newFile(key: String): File {
            return File(cacheDir, key.hashCode().toString() + "")
        }

        fun remove(key: String): Boolean {
            val image = get(key)
            return image.delete()
        }

        fun clear() {
            try {
                lastUsageDates.clear()
                cacheSize.set(0)
                val files = cacheDir.listFiles()
                if (files != null) {
                    for (f in files) {
                        f.delete()
                    }
                }
            } catch (e: Exception) {
                e.printStackTraceOnDebug()
            }

        }

        /**
         * 移除旧的文件
         */
        private fun removeNext(): Long {
            try {
                if (lastUsageDates.isEmpty()) {
                    return 0
                }

                var oldestUsage: Long? = null
                var mostLongUsedFile: File? = null
                val entries = lastUsageDates.entries
                synchronized(lastUsageDates) {
                    for ((key, lastValueUsage) in entries) {
                        if (mostLongUsedFile == null) {
                            mostLongUsedFile = key
                            oldestUsage = lastValueUsage
                        } else {
                            if (lastValueUsage < oldestUsage!!) {
                                oldestUsage = lastValueUsage
                                mostLongUsedFile = key
                            }
                        }
                    }
                }

                var fileSize: Long = 0
                if (mostLongUsedFile != null) {
                    fileSize = calculateSize(mostLongUsedFile)
                    if (mostLongUsedFile.delete()) {
                        lastUsageDates.remove(mostLongUsedFile)
                    }
                }
                return fileSize
            } catch (e: Exception) {
                e.printStackTraceOnDebug()
                return 0
            }

        }

        private fun calculateSize(file: File): Long {
            return file.length()
        }
    }

}
