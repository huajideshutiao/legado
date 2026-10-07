// android.os.Bundle JVM 空壳 stub: 承载扩展 Activity/Intent 的键值面 (HashMap 实现, 无 Parcel 语义)。
package android.os

class Bundle {

    private val map = HashMap<String, Any?>()

    fun putString(key: String?, value: String?) {
        map[key.orEmpty()] = value
    }

    fun getString(key: String?): String? = map[key.orEmpty()] as? String

    fun getString(key: String?, defaultValue: String?): String =
        (map[key.orEmpty()] as? String) ?: defaultValue.orEmpty()

    fun putInt(key: String?, value: Int) {
        map[key.orEmpty()] = value
    }

    fun getInt(key: String?): Int = map[key.orEmpty()] as? Int ?: 0

    fun getInt(key: String?, defaultValue: Int): Int = map[key.orEmpty()] as? Int ?: defaultValue

    fun putBoolean(key: String?, value: Boolean) {
        map[key.orEmpty()] = value
    }

    fun getBoolean(key: String?): Boolean = map[key.orEmpty()] as? Boolean ?: false

    fun getBoolean(key: String?, defaultValue: Boolean): Boolean =
        map[key.orEmpty()] as? Boolean ?: defaultValue

    fun putLong(key: String?, value: Long) {
        map[key.orEmpty()] = value
    }

    fun getLong(key: String?): Long = map[key.orEmpty()] as? Long ?: 0L

    fun getLong(key: String?, defaultValue: Long): Long = map[key.orEmpty()] as? Long ?: defaultValue

    fun putStringArrayList(key: String?, value: ArrayList<String>?) {
        map[key.orEmpty()] = value
    }

    @Suppress("UNCHECKED_CAST")
    fun getStringArrayList(key: String?): ArrayList<String>? =
        map[key.orEmpty()] as? ArrayList<String>

    fun putStringArray(key: String?, value: Array<String>?) {
        map[key.orEmpty()] = value
    }

    fun getStringArray(key: String?): Array<String>? = map[key.orEmpty()] as? Array<String>

    fun containsKey(key: String?): Boolean = map.containsKey(key.orEmpty())

    fun remove(key: String?) {
        map.remove(key.orEmpty())
    }

    fun keySet(): Set<String> = map.keys

    fun size(): Int = map.size

    fun isEmpty(): Boolean = map.isEmpty()

    fun clear() {
        map.clear()
    }
}
