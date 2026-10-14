package io.legado.app.ui.debug

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.IntentData
import io.legado.app.help.sourceLoginOverlayPayload
import io.legado.app.help.update.UpdateCheckInfo
import io.legado.app.ui.about.CRASH_NOTICE_OVERLAY_KEY
import io.legado.app.ui.association.OpenUrlConfirmPayload
import io.legado.app.ui.book.manga.extension.MangaExtensionServiceProviders
import io.legado.app.ui.config.MODE_EDIT_PREFS
import io.legado.app.ui.root.AppOverlay
import io.legado.app.ui.root.AppRoute
import io.legado.app.ui.root.encodeBookVariableOverlayPayload
import io.legado.app.ui.root.encodeSourceVariableOverlayPayload
import io.legado.app.ui.route.encodeReviewListDialogPayload
import io.legado.app.utils.encodeStringMap
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.add
import legado.ui.generated.resources.assists_key_config
import legado.ui.generated.resources.bookshelf_layout
import legado.ui.generated.resources.bottom_nav_config
import legado.ui.generated.resources.change_cover_source
import legado.ui.generated.resources.check_source_config
import legado.ui.generated.resources.copy_to_clipboard
import legado.ui.generated.resources.crash_log
import legado.ui.generated.resources.customize_day_theme
import legado.ui.generated.resources.default_cover
import legado.ui.generated.resources.dict_rule
import legado.ui.generated.resources.export
import legado.ui.generated.resources.export_config
import legado.ui.generated.resources.export_success
import legado.ui.generated.resources.group_select
import legado.ui.generated.resources.help
import legado.ui.generated.resources.import_file_name
import legado.ui.generated.resources.import_on_line
import legado.ui.generated.resources.log
import legado.ui.generated.resources.login_source
import legado.ui.generated.resources.manga_extension_setting
import legado.ui.generated.resources.search_book_key
import legado.ui.generated.resources.set_book_variable
import legado.ui.generated.resources.set_source_variable
import legado.ui.generated.resources.source_filter_rule
import legado.ui.generated.resources.theme_list
import legado.ui.generated.resources.txt_toc_rule

/**
 * 走覆盖物栈的弹窗预览清单。
 *
 * 每一条对应产品里真实存在的一个覆盖物键, 点开即按该键入覆盖物栈, 与真实调用完全同一条路径
 * (不另造挂载点, 不复制弹窗实现)。依赖运行时身份的键在此取当前库里的真实记录; 取不到数据的
 * 条目显示为空态, 不伪造数据。
 *
 * 清单在进入页面时构建一次 (库读在 IO 线程), 故本函数是 suspend 的。
 *
 * 每个弹窗只列一条: 同一个弹窗经不同渠道 (深链/平台文件选择器/网络导入) 能到达的, 由覆盖的形制
 * 层数最多的那条承载; 内嵌清单里同样的弹窗不再重复。
 */
internal suspend fun buildOverlayEntries(): List<DialogPreviewEntry.Overlay> {
    val entries = mutableListOf<DialogPreviewEntry.Overlay>()

    /**
     * 覆盖物条目的登记: [key] 同时用于入栈与列表第二行的真实标识 (只写一处, 不重复字面量),
     * [content] 拿这个 key 造出覆盖物。
     */
    fun alert(id: String, name: DialogPreviewName, key: String, content: (key: String) -> AppOverlay.Dialog) {
        entries += DialogPreviewEntry.Overlay(DialogPreviewGroup.ALERT, id, name, key) { content(key) }
    }

    fun dialog(id: String, name: DialogPreviewName, key: String, content: (key: String) -> AppOverlay.Dialog) {
        entries += DialogPreviewEntry.Overlay(DialogPreviewGroup.DIALOG, id, name, key) { content(key) }
    }

    /** 贴底形制但产品里以 Dialog 键入栈的条目 (如段评列表: 键是 Dialog, 内容是贴底面板)。 */
    fun sheet(id: String, name: DialogPreviewName, key: String, content: (key: String) -> AppOverlay.Dialog) {
        entries += DialogPreviewEntry.Overlay(DialogPreviewGroup.SHEET, id, name, key) { content(key) }
    }

    /** 真·半屏覆盖物 ([AppOverlay.Sheet]): 产品里经该键入半屏弹层。 */
    fun sheetOverlay(id: String, name: DialogPreviewName, key: String, content: (key: String) -> AppOverlay.Sheet) {
        entries += DialogPreviewEntry.Overlay(DialogPreviewGroup.SHEET, id, name, key) { content(key) }
    }

    // ===== 无需数据: 打开即见真实内容 =====
    // 帮助与更新日志: 同一个弹窗 (帮助壳 + 文本查看), 只留一条
    dialog("help_app", titleOf(Res.string.help), "help") { AppOverlay.Dialog(it, payload = "appHelp") }
    dialog("crash_logs", titleOf(Res.string.crash_log), "crash_logs") { AppOverlay.Dialog(it) }
    dialog("crash_notice", nameText("崩溃后提示"), CRASH_NOTICE_OVERLAY_KEY) { AppOverlay.Dialog(it) }
    dialog("app_log", titleOf(Res.string.log), "app_log") { AppOverlay.Dialog(it) }
    dialog("theme_list", titleOf(Res.string.theme_list), "theme_list") { AppOverlay.Dialog(it) }
    dialog("theme_customize_day", titleOf(Res.string.customize_day_theme), "theme_customize") {
        AppOverlay.Dialog(it, payload = "$MODE_EDIT_PREFS,-1,false")
    }
    dialog("bookshelf_layout", titleOf(Res.string.bookshelf_layout), "bookshelf_layout") { AppOverlay.Dialog(it) }
    dialog("bottom_nav_config", titleOf(Res.string.bottom_nav_config), "bottom_nav_config") { AppOverlay.Dialog(it) }
    dialog("check_source_config", titleOf(Res.string.check_source_config), "check_source_config") { AppOverlay.Dialog(it) }
    // 半屏浏览器: 需要一个真实地址 (打开即真实加载), 用中性示例域名
    sheetOverlay("web_view", nameText("半屏浏览器"), "web_view") {
        AppOverlay.Sheet(
            key = it,
            payload = PREVIEW_WEB_URL,
            webView = AppRoute.WebView(url = PREVIEW_WEB_URL, title = "半屏浏览器"),
        )
    }
    dialog("direct_link_upload_config", nameText("直链上传配置"), "direct_link_upload_config") {
        AppOverlay.Dialog(it)
    }
    dialog("keyboard_assists", titleOf(Res.string.assists_key_config), "keyboardAssistsConfig") { AppOverlay.Dialog(it) }
    dialog("default_cover_gallery_day", titleOf(Res.string.default_cover), "default_cover_gallery") {
        AppOverlay.Dialog(it, payload = "0")
    }
    dialog("import_file_name", titleOf(Res.string.import_file_name), "import_file_name") { AppOverlay.Dialog(it) }
    dialog("book_export_format", titleOf(Res.string.export_config), "book_export_format") { AppOverlay.Dialog(it) }
    dialog("export_dispatch", titleOf(Res.string.export), "exportDispatch") {
        AppOverlay.Dialog(
            it,
            payload = encodeStringMap(
                mapOf(
                    "fileName" to "legado_preview.json",
                    "content" to """{"preview":true}""",
                    "contentType" to "application/json",
                )
            ),
        )
    }
    dialog("export_success", titleOf(Res.string.export_success), "exportSuccess") {
        AppOverlay.Dialog(
            it,
            payload = encodeStringMap(mapOf("value" to "预览: 导出成功")),
        )
    }
    dialog("copy_confirm", titleOf(Res.string.copy_to_clipboard), "copy_confirm") { AppOverlay.Dialog(it, payload = "preview") }

    // 导入类只留网络导入这一条: 它先弹取地址、再弹勾选导入, 一条对照两层形制;
    // 本地导入 (平台文件选择器) 与深链导入最终落到的都是同一个勾选导入弹窗, 不再重复列。
    dialog("dict_rule_import_online", titleOf(Res.string.import_on_line), "dictRuleImportOnline") {
        AppOverlay.Dialog(it)
    }

    // 规则类: 编辑 / 列表 (数据由覆盖物内自取, 取不到即空表)
    dialog("dict_rule_edit", titleOf(Res.string.dict_rule), "dictRuleEdit") { AppOverlay.Dialog(it) }
    dialog("txt_toc_rule_edit", titleOf(Res.string.txt_toc_rule), "txtTocRuleEdit") { AppOverlay.Dialog(it) }
    dialog("source_filter_edit", titleConcat(Res.string.add, Res.string.source_filter_rule), "sourceFilterRuleEdit") {
        AppOverlay.Dialog(it)
    }
    dialog("source_filter_list", titleOf(Res.string.source_filter_rule), "sourceFilterRuleList") {
        AppOverlay.Dialog(it)
    }

    // 更新弹窗 (payload 走 IntentData 侧信道, 样本版本号不代表任何真实发布)
    dialog("update_dialog", nameText("更新弹窗"), "updateDialog") {
        AppOverlay.Dialog(
            it,
            payload = IntentData.put(
                UpdateCheckInfo(
                    versionName = "0.0.0-preview",
                    releaseNote = "预览: 更新说明正文",
                    downloadUrl = "",
                    fileName = "",
                    landingUrl = "",
                )
            ),
        )
    }
    // 跳转确认 (payload 同样走侧信道)
    dialog("open_url_confirm", nameText("跳转确认"), "openUrlConfirm") {
        AppOverlay.Dialog(
            it,
            payload = IntentData.put(
                OpenUrlConfirmPayload(url = "https://example.com/")
            ),
        )
    }
    // 朗读引擎编辑 (payload = 引擎 id, null 为新建)
    dialog("http_tts_edit", nameText("朗读引擎编辑"), "httpTtsEdit") {
        AppOverlay.Dialog(it, payload = null)
    }
    // 插件设置 (payload = 扩展包名; 取已装第一个, 无则空态)
    dialog("extension_pref", titleOf(Res.string.manga_extension_setting), "extensionPref") {
        val pkgName = MangaExtensionServiceProviders.getOrNull()
            ?.state?.value?.installed?.firstOrNull()?.pkgName
        AppOverlay.Dialog(it, payload = pkgName)
    }

    // ===== 依赖数据: 取当前库里的第一条真实记录 =====
    val appDb = AppDbProviders.get()
    val book = previewRead("书籍样本") { appDb.bookDao.all().firstOrNull() }
    val source = previewRead("书源样本") { appDb.bookSourceDao.all().firstOrNull() }
    val group = previewRead("分组样本") { appDb.bookGroupDao.all().firstOrNull() }
    // 段评列表载荷含目录查询 (挂起函数), 与上面三条库读一致在清单构建期取一次,
    // 条目回调保持非挂起 (点开只做入栈)
    val reviewListPayloadValue = book?.let { reviewListPayload(it) }

    alert("check_book_source", titleOf(Res.string.search_book_key), "check_book_source") {
        AppOverlay.Dialog(it, payload = source?.bookSourceUrl.orEmpty())
    }
    dialog("source_variable", titleOf(Res.string.set_source_variable), "sourceVariable") {
        AppOverlay.Dialog(
            it,
            payload = source?.let { src -> encodeSourceVariableOverlayPayload(src) },
        )
    }
    dialog("book_variable", titleOf(Res.string.set_book_variable), "bookVariable") {
        AppOverlay.Dialog(
            it,
            payload = book?.let { b -> encodeBookVariableOverlayPayload(b, source ?: BookSource()) },
        )
    }
    alert("group_select", titleOf(Res.string.group_select), "group_select") {
        AppOverlay.Dialog(it, payload = (group?.groupId ?: 0L).toString())
    }
    dialog("change_cover", titleOf(Res.string.change_cover_source), "change_cover") {
        AppOverlay.Dialog(
            it,
            payload = "${book?.name.orEmpty()}\n${book?.author.orEmpty()}",
        )
    }
    dialog("source_login", titleOf(Res.string.login_source, source?.getTag().orEmpty()), "sourceLogin") {
        AppOverlay.Dialog(
            it,
            payload = source?.let { src -> sourceLoginOverlayPayload(src.bookSourceUrl) },
        )
    }
    sheet("review_list", nameText("段评/书评列表"), "review_list") {
        AppOverlay.Dialog(it, payload = reviewListPayloadValue)
    }
    dialog("photo", nameText("大图查看"), "photo") {
        AppOverlay.Dialog(
            it,
            payload = book?.coverUrl.orEmpty(),
            sourceOrigin = book?.origin,
        )
    }

    return entries
}

/**
 * 段评列表预览载荷: 用与产品侧同一个编码函数产出 (书籍 + 章节 + 段落索引),
 * 章节取该书目录首章 (取不到时章节为空, 列表内无评论即显示空态)。
 */
private suspend fun reviewListPayload(book: Book): String {
    val chapter = previewRead("目录样本") {
        AppDbProviders.get().bookChapterDao.getChapterList(book.bookUrl).firstOrNull()
    }
    return encodeReviewListDialogPayload(book, chapter, paragraphIndex = 0)
}

/** 半屏浏览器预览用的中性地址 (不指向任何第三方站点)。 */
private const val PREVIEW_WEB_URL = "https://example.com"
