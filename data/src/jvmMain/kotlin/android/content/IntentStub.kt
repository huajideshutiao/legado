// android.content.Intent/ActivityNotFoundException JVM 空壳 stub: 扩展的"打开网页/跳转"型
// 动作构造 Intent 后交给宿主 (桌面端 Context.startActivity 为空操作), 仅需类解析与字段面。
package android.content

import android.net.Uri

open class Intent {

    private var action: String? = null
    private var data: Uri? = null
    private var flags: Int = 0
    private var packageName: String? = null
    private val extras = HashMap<String, Any?>()

    constructor()

    constructor(action: String?) {
        this.action = action
    }

    constructor(action: String?, uri: Uri?) {
        this.action = action
        data = uri
    }

    open fun setAction(action: String?): Intent {
        this.action = action
        return this
    }

    open fun getAction(): String? = action

    open fun setData(data: Uri?): Intent {
        this.data = data
        return this
    }

    open fun getData(): Uri? = data

    open fun putExtra(name: String?, value: String?): Intent {
        extras[name.orEmpty()] = value
        return this
    }

    open fun putExtra(name: String?, value: Int): Intent {
        extras[name.orEmpty()] = value
        return this
    }

    open fun putExtra(name: String?, value: Boolean): Intent {
        extras[name.orEmpty()] = value
        return this
    }

    open fun putExtra(name: String?, value: Long): Intent {
        extras[name.orEmpty()] = value
        return this
    }

    open fun putStringArrayListExtra(name: String?, value: ArrayList<String>?): Intent {
        extras[name.orEmpty()] = value
        return this
    }

    open fun getStringExtra(name: String?): String? = extras[name.orEmpty()] as? String

    open fun getIntExtra(name: String?, defaultValue: Int): Int =
        extras[name.orEmpty()] as? Int ?: defaultValue

    open fun getBooleanExtra(name: String?, defaultValue: Boolean): Boolean =
        extras[name.orEmpty()] as? Boolean ?: defaultValue

    open fun getLongExtra(name: String?, defaultValue: Long): Long =
        extras[name.orEmpty()] as? Long ?: defaultValue

    @Suppress("UNCHECKED_CAST")
    open fun getStringArrayListExtra(name: String?): ArrayList<String>? =
        extras[name.orEmpty()] as? ArrayList<String>

    open fun hasExtra(name: String?): Boolean = extras.containsKey(name.orEmpty())

    open fun removeExtra(name: String?) {
        extras.remove(name.orEmpty())
    }

    open fun addFlags(flags: Int): Intent {
        this.flags = this.flags or flags
        return this
    }

    open fun setFlags(flags: Int): Intent {
        this.flags = flags
        return this
    }

    open fun getFlags(): Int = flags

    open fun setPackage(packageName: String?): Intent {
        this.packageName = packageName
        return this
    }

    open fun getPackage(): String? = packageName
}

class ActivityNotFoundException : RuntimeException {

    constructor() : super()

    constructor(message: String?) : super(message)
}
