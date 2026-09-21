package io.legado.app

import io.legado.app.ui.association.LegadoDeepLinkHandler

/**
 * iOS 端 legado:// deep link 入口 (Swift 侧调用: `LegadoDeepLinkIosKt.handleLegadoDeepLink(url:)`)。
 *
 * # 调用链
 *
 * ```
 * 系统打开 legado://import/bookSource?src=... (Info.plist CFBundleURLTypes 注册, 见 project.yml)
 *   └── iOSApp.swift WindowGroup.onOpenURL { url in ... }
 *         └── 本函数 → commonMain LegadoDeepLinkHandler.handle(url)
 *               └── LegadoDeepLink.parse 解析 (对照 app 端 FileAssociationFragment.handleOnLineImport)
 *                     └── LegadoDeepLinkHandler.pending (StateFlow) 记录待导入请求
 * ```
 *
 * 消费侧: [io.legado.app.MainViewController] 尾部挂 [io.legado.app.ui.association.DeepLinkImportHost],
 * collect `LegadoDeepLinkHandler.pending` 后按类型走 ImportXxxViewModelShared + ImportItemsDialog
 * (与 LegadoApp 内手动网络导入同链), 完成/取消均 `LegadoDeepLinkHandler.consume()`。
 * 冷启动先投递后组合也不丢 (pending 是 StateFlow)。
 *
 * @return true=已识别并记录; false=非 legado/yuedu scheme 或缺 src 参数 (调用方可透传给其他 handler)
 */
fun handleLegadoDeepLink(url: String): Boolean = LegadoDeepLinkHandler.handle(url)
