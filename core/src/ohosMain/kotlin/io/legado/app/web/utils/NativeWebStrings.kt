package io.legado.app.web.utils

/**
 * [WebStrings] 的鸿蒙实现。
 *
 * 暂用硬编码中文 (与 app 端 values-zh/strings.xml `cannot_empty` 文案一致,
 * 与桌面端 DesktopWebStrings 同源), 后续接入 i18n 资源后替换。
 */
class NativeWebStrings : WebStrings {

    override val cannotEmpty: String = "输入不能为空"
}

/**
 * 鸿蒙宿主启动早期注册 [WebStrings] 的实现。
 *
 * 模式参考 [registerDesktopWebStrings] / [io.legado.app.help.service.ServiceLaunchers]。
 */
fun registerNativeWebStrings() {
    WebStringsProviders.register(NativeWebStrings())
}
