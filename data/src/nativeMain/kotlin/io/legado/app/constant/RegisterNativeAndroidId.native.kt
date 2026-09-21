package io.legado.app.constant

import io.legado.app.help.config.PreferenceProviders
import io.legado.app.utils.randomUUIDString

/**
 * native (iOS/鸿蒙) 设备标识注入: 给 [AndroidIdHolder.value] 赋 ≥16 字符稳定标识。
 *
 * 默认值 "null" 仅 4 字符, BaseSource 登录信息加密 `androidId.encodeToByteArray(0, 16)`
 * 必越界被吞, 登录信息完全无法持久化。
 *
 * @param platformId 平台侧稳定设备 id (iOS 传 UIDevice.identifierForVendor UUID);
 *   为空/过短时回退到首启生成并经 [PreferenceProviders] 落盘的 UUID (跨启动稳定)。
 *   须在 PreferenceProviders 注册之后调用。
 */
fun registerNativeAndroidId(platformId: String? = null) {
    AndroidIdHolder.value = platformId?.takeIf { it.length >= 16 } ?: persistedDeviceId()
}

private fun persistedDeviceId(): String {
    val prefs = PreferenceProviders.get()
    val existing = prefs.getString("nativeDeviceId", "")
    if (existing.length >= 16) return existing
    return randomUUIDString().also { prefs.putString("nativeDeviceId", it) }
}
