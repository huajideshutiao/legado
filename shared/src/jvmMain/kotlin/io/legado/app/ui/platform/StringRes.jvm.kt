package io.legado.app.ui.platform

/**
 * [stringRes] 的桌面 JVM actual: 无 R.string 资源体系, 返回空串占位。
 *
 * 桌面端 EditEntity 等 Android 旧 widget 不会被使用, 此处仅满足 expect 契约。
 */
actual fun stringRes(resId: Int): String = ""
