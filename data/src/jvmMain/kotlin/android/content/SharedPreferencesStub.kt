// android.* JVM stub: SharedPreferences 的内存态良性实现 (扩展读偏好得默认值, 写不持久)。
package android.content

import java.util.concurrent.ConcurrentHashMap

interface SharedPreferences {

    fun getAll(): Map<String, *>

    fun getString(key: String?, defValue: String?): String?

    fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>?

    fun getInt(key: String?, defValue: Int): Int

    fun getLong(key: String?, defValue: Long): Long

    fun getFloat(key: String?, defValue: Float): Float

    fun getBoolean(key: String?, defValue: Boolean): Boolean

    fun contains(key: String?): Boolean

    fun edit(): Editor

    fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener?) {}

    fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener?) {}

    interface OnSharedPreferenceChangeListener {
        fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?)
    }

    interface Editor {
        fun putString(key: String?, value: String?): Editor

        fun putStringSet(key: String?, values: MutableSet<String>?): Editor

        fun putInt(key: String?, value: Int): Editor

        fun putLong(key: String?, value: Long): Editor

        fun putFloat(key: String?, value: Float): Editor

        fun putBoolean(key: String?, value: Boolean): Editor

        fun remove(key: String?): Editor

        fun clear(): Editor

        fun commit(): Boolean

        fun apply()
    }
}

/**
 * 按文件名隔离的内存偏好桶: 扩展 <clinit> (keiyoushi utils injectLazy→getSharedPreferences)
 * 与读偏好路径拿到稳定默认值, 写操作进程内生效。
 */
class SharedPrefsStub(private val name: String?) : SharedPreferences {

    // stub 不承诺并发安全 (Android 端 SharedPreferences 由系统保证, JVM stub 只满足扩展读默认值/写内存)
    private val store = HashMap<String, Any?>()

    override fun getAll(): Map<String, *> = store.toMap()

    override fun getString(key: String?, defValue: String?): String? =
        store[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        store[key] as? MutableSet<String> ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = store[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = store[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = store[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        store[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = store.containsKey(key)

    override fun edit(): SharedPreferences.Editor = StubEditor()

    private inner class StubEditor : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private val removals = LinkedHashSet<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor =
            apply { pending[key!!] = value!! }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
            apply { pending[key!!] = values!! }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
            apply { pending[key!!] = value }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
            apply { pending[key!!] = value }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
            apply { pending[key!!] = value }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
            apply { pending[key!!] = value }

        override fun remove(key: String?): SharedPreferences.Editor =
            apply { removals.add(key!!) }

        override fun clear(): SharedPreferences.Editor = apply { clearAll = true }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearAll) store.clear()
            removals.forEach { store.remove(it) }
            pending.forEach { (k, v) -> store[k] = v }
        }
    }
}
