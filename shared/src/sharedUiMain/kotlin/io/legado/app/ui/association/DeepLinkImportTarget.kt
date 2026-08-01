package io.legado.app.ui.association

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow

/**
 * legado:// deep link 导入目标: 按 [DeepLinkImportType] 归一化各 Import*ViewModelShared 的差异,
 * 供 iOS/鸿蒙的 deep link 宿主用同一段代码分发 (对照 desktop `DesktopImportVm` 的同名归一化)。
 *
 * 抹平三处差异:
 * - 入口方法名: 书源/目录规则/字典/语音/主题是 `importSource(text)`, 替换规则是 `import(text)`;
 * - 列表适配器: 各自的 Import*ItemsVm (字段名 allSources/allRules 不同);
 * - 对话框标题 key。
 *
 * 三个特殊类型不走这套"列表勾选导入"模型 (加书架是直接跳详情页, 排版配置是一步导入完成,
 * 都没有"勾选新增/更新条目"的对话框), 由 [DeepLinkImportHost] 直接调
 * [SchemeImportOps.determineType] / [AddToBookshelfShared] / [SchemeImportOps.importReadConfig]
 * 分流处理, 不在本类构造 [DeepLinkImportTarget]:
 * - ADD_TO_BOOKSHELF: 直接跳 [io.legado.app.ui.root.AppRoute.BookInfo], 无列表;
 * - READ_CONFIG: 一步导入完成 (覆盖/追加 configList), 无列表;
 * - UNKNOWN: 先 [SchemeImportOps.determineType] 嗅探, zip 分支直接完成 (同 READ_CONFIG),
 *   JSON 分支嗅探出具体类型后递归为对应枚举, 才落到本类。
 */
class DeepLinkImportTarget private constructor(
    /** 勾选列表适配器, 直接传给 [ImportItemsDialog]。 */
    val items: ImportItemsVm,
    /** 对话框标题 key (交调用方 rememberString 取文案)。 */
    val titleKey: String,
    /** 错误流, 值格式 `"ImportError:${msg}"`, 与手动导入同源。 */
    val errorState: SharedFlow<String>,
    /** 成功流, 值为解析出的条目数, 0 表示格式错误。 */
    val successState: SharedFlow<Int>,
    private val startImportFn: (String) -> Unit,
    /** 书源类型时非 null, 调用方改用 [ImportBookSourceItemsDialog] 以带上"选中新增/更新源"菜单。 */
    val bookSourceVm: ImportBookSourceViewModelShared? = null,
) {

    /** 触发下载 → 解析 → 与本地库比对 (与手动网络导入同一条链)。 */
    fun startImport(src: String) = startImportFn(src)

    companion object {

        /** 按类型创建目标, 不支持的类型返回 null。每条 deep link 请求新建, 避免 state 残留。 */
        fun of(type: DeepLinkImportType, scope: CoroutineScope): DeepLinkImportTarget? = when (type) {
            DeepLinkImportType.BOOK_SOURCE -> ImportBookSourceViewModelShared(scope).let { vm ->
                DeepLinkImportTarget(
                    ImportBookSourceItemsVm(vm), "import_book_source",
                    vm.errorState, vm.successState, vm::importSource, vm,
                )
            }

            DeepLinkImportType.REPLACE_RULE -> ImportReplaceRuleViewModelShared(scope).let { vm ->
                DeepLinkImportTarget(
                    ImportReplaceRuleItemsVm(vm), "import_replace_rule",
                    vm.errorState, vm.successState, vm::import,
                )
            }

            DeepLinkImportType.TXT_TOC_RULE -> ImportTxtTocRuleViewModelShared(scope).let { vm ->
                DeepLinkImportTarget(
                    ImportTxtTocRuleItemsVm(vm), "import_txt_toc_rule",
                    vm.errorState, vm.successState, vm::importSource,
                )
            }

            DeepLinkImportType.HTTP_TTS -> ImportHttpTtsViewModelShared(scope).let { vm ->
                DeepLinkImportTarget(
                    ImportHttpTtsItemsVm(vm), "import_tts",
                    vm.errorState, vm.successState, vm::importSource,
                )
            }

            DeepLinkImportType.DICT_RULE -> ImportDictRuleViewModelShared(scope).let { vm ->
                DeepLinkImportTarget(
                    ImportDictRuleItemsVm(vm), "import_dict_rule",
                    vm.errorState, vm.successState, vm::importSource,
                )
            }

            DeepLinkImportType.THEME -> ImportThemeViewModelShared(scope).let { vm ->
                DeepLinkImportTarget(
                    ImportThemeItemsVm(vm), "import_theme",
                    vm.errorState, vm.successState, vm::importSource,
                )
            }

            DeepLinkImportType.ADD_TO_BOOKSHELF,
            DeepLinkImportType.READ_CONFIG,
            DeepLinkImportType.UNKNOWN,
            -> null
        }
    }
}
