package io.legado.app.help

@Suppress("unused")
class EventMessage {

    var what: Int? = null
    var tag: String? = null
    var obj: Any? = null

    /**
     * 原版既有本方法, 原版与本仓均无消费方, 保留以对齐原版公开 API。
     * 未使用警告由类上的 `@Suppress("unused")` 统一压制, 方法不再单独标注。
     */
    fun isFrom(tag: String): Boolean {
        // 原实现使用 android.text.TextUtils.equals(a, b)，其在 Java 层对 null 均安全。
        // Kotlin 的 == 同样先判 null 再调用 equals，语义等价且可跨平台使用。
        return this.tag == tag
    }

    /**
     * 原版既有本方法, 原版与本仓均无消费方, 保留以对齐原版公开 API。
     * 未使用警告由类上的 `@Suppress("unused")` 统一压制, 方法不再单独标注。
     */
    fun maybeFrom(vararg tags: String): Boolean {
        return listOf(*tags).contains(tag)
    }

    companion object {

        fun obtain(tag: String): EventMessage {
            val message = EventMessage()
            message.tag = tag
            return message
        }

        fun obtain(what: Int): EventMessage {
            val message = EventMessage()
            message.what = what
            return message
        }

        fun obtain(what: Int, obj: Any): EventMessage {
            val message = EventMessage()
            message.what = what
            message.obj = obj
            return message
        }

        fun obtain(tag: String, obj: Any): EventMessage {
            val message = EventMessage()
            message.tag = tag
            message.obj = obj
            return message
        }
    }

}
