package io.legado.desktop.help

import io.legado.app.constant.AndroidIdHolder
import io.legado.app.utils.MD5Utils

/**
 * 桌面端机器标识注入 (对应 app 端 `JsEnginesAndroid` 注入 `Settings.Secure.ANDROID_ID`)。
 *
 * [AndroidIdHolder] 默认值 "null" 只有 4 字符, 而 `BaseSource.getLoginInfo/putLoginInfo`
 * 取 `androidId.encodeToByteArray(0, 16)` 作 AES 密钥, 会下标越界被 try/catch 吞掉,
 * 导致桌面端书源登录信息无法保存/读取。
 *
 * 标识由 `user.name + os.name + os.arch + user.home` 做 MD5 派生 (32 位小写 hex, 纯 ASCII),
 * 全部来自本机系统属性, 无网络/文件依赖, 跨启动稳定 —— 否则已存的登录信息会解密失败。
 */
fun registerDesktopAndroidId() {
    val seed = buildString {
        append(System.getProperty("user.name").orEmpty())
        append('|')
        append(System.getProperty("os.name").orEmpty())
        append('|')
        append(System.getProperty("os.arch").orEmpty())
        append('|')
        append(System.getProperty("user.home").orEmpty())
    }
    AndroidIdHolder.value = MD5Utils.md5Encode(seed)
}
