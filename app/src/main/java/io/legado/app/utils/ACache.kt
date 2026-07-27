//Copyright (c) 2017. 章钦豪. All rights reserved.
package io.legado.app.utils

import android.graphics.Bitmap
import android.graphics.Bitmap.createBitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toDrawable
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 本地缓存 (app 端 Android 专属层)。
 *
 * 继承 shared [ACacheBase] (纯 JDK: ACacheManager + Utils date info + String/ByteArray 读写),
 * 本类仅保留 Android 平台专属重载:
 * - Bitmap/Drawable 读写 (依赖 android.graphics.Bitmap/Drawable)
 * - JSONObject/JSONArray 读写 (依赖 org.json, Android 平台特有, 参照 ServerExt.kt 模式)
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

    // =======================================
    // ========== JSONObject 数据 读写 =========
    // =======================================

    /**
     * 保存 JSONObject数据 到 缓存中
     *
     * @param key   保存的key
     * @param value 保存的JSON数据
     */
    fun put(key: String, value: JSONObject) {
        put(key, value.toString())
    }

    /**
     * 保存 JSONObject数据 到 缓存中
     *
     * @param key      保存的key
     * @param value    保存的JSONObject数据
     * @param saveTime 保存的时间，单位：秒
     */
    fun put(key: String, value: JSONObject, saveTime: Int) {
        put(key, value.toString(), saveTime)
    }

    /**
     * 读取JSONObject数据
     *
     * @return JSONObject数据
     */
    fun getAsJSONObject(key: String): JSONObject? {
        val json = getAsString(key) ?: return null
        return try {
            JSONObject(json)
        } catch (e: Exception) {
            null
        }
    }

    // =======================================
    // ============ JSONArray 数据 读写 =============
    // =======================================

    /**
     * 保存 JSONArray数据 到 缓存中
     *
     * @param key   保存的key
     * @param value 保存的JSONArray数据
     */
    fun put(key: String, value: JSONArray) {
        put(key, value.toString())
    }

    /**
     * 保存 JSONArray数据 到 缓存中
     *
     * @param key      保存的key
     * @param value    保存的JSONArray数据
     * @param saveTime 保存的时间，单位：秒
     */
    fun put(key: String, value: JSONArray, saveTime: Int) {
        put(key, value.toString(), saveTime)
    }

    /**
     * 读取JSONArray数据
     *
     * @return JSONArray数据
     */
    fun getAsJSONArray(key: String): JSONArray? {
        val json = getAsString(key)
        return try {
            JSONArray(json)
        } catch (e: Exception) {
            null
        }

    }

    // =======================================
    // ============== bitmap 数据 读写 =============
    // =======================================

    /**
     * 保存 bitmap 到 缓存中
     *
     * @param key   保存的key
     * @param value 保存的bitmap数据
     */
    fun put(key: String, value: Bitmap) {
        put(key, Utils.bitmap2Bytes(value))
    }

    /**
     * 保存 bitmap 到 缓存中
     *
     * @param key      保存的key
     * @param value    保存的 bitmap 数据
     * @param saveTime 保存的时间，单位：秒
     */
    fun put(key: String, value: Bitmap, saveTime: Int) {
        put(key, Utils.bitmap2Bytes(value), saveTime)
    }

    /**
     * 读取 bitmap 数据
     *
     * @return bitmap 数据
     */
    fun getAsBitmap(key: String): Bitmap? {
        return if (getAsBinary(key) == null) {
            null
        } else Utils.bytes2Bitmap(getAsBinary(key)!!)
    }

    // =======================================
    // ============= drawable 数据 读写 =============
    // =======================================

    /**
     * 保存 drawable 到 缓存中
     *
     * @param key   保存的key
     * @param value 保存的drawable数据
     */
    fun put(key: String, value: Drawable) {
        put(key, Utils.drawable2Bitmap(value))
    }

    /**
     * 保存 drawable 到 缓存中
     *
     * @param key      保存的key
     * @param value    保存的 drawable 数据
     * @param saveTime 保存的时间，单位：秒
     */
    fun put(key: String, value: Drawable, saveTime: Int) {
        put(key, Utils.drawable2Bitmap(value), saveTime)
    }

    /**
     * 读取 Drawable 数据
     *
     * @return Drawable 数据
     */
    fun getAsDrawable(key: String): Drawable? {
        return if (getAsBinary(key) == null) {
            null
        } else Utils.bitmap2Drawable(
            Utils.bytes2Bitmap(
                getAsBinary(key)!!
            )
        )
    }

    /**
     * Bitmap/Drawable 转换工具 (Android 专属, 依赖 android.graphics.Bitmap/Drawable)。
     *
     * date info 编解码部分已下沉 [ACacheBase]。
     */
    private object Utils {

        /*
         * Bitmap → byte[]
         */
        fun bitmap2Bytes(bm: Bitmap): ByteArray {
            val byteArrayOutputStream = ByteArrayOutputStream()
            bm.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream)
            return byteArrayOutputStream.toByteArray()
        }

        /*
         * byte[] → Bitmap
         */
        fun bytes2Bitmap(b: ByteArray): Bitmap? {
            return if (b.isEmpty()) {
                null
            } else BitmapFactory.decodeByteArray(b, 0, b.size)
        }

        /*
         * Drawable → Bitmap
         */
        fun drawable2Bitmap(drawable: Drawable): Bitmap {
            // 取 drawable 的长宽
            val w = drawable.intrinsicWidth
            val h = drawable.intrinsicHeight
            // 取 drawable 的颜色格式
            @Suppress("DEPRECATION")
            val config = if (drawable.opacity != PixelFormat.OPAQUE)
                Bitmap.Config.ARGB_8888
            else
                Bitmap.Config.RGB_565
            // 建立对应 bitmap
            val bitmap = createBitmap(w, h, config)
            // 建立对应 bitmap 的画布
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, w, h)
            // 把 drawable 内容画到画布中
            drawable.draw(canvas)
            return bitmap
        }

        /*
         * Bitmap → Drawable
         */
        fun bitmap2Drawable(bm: Bitmap?): Drawable? {
            return bm?.toDrawable(appCtx.resources)
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
