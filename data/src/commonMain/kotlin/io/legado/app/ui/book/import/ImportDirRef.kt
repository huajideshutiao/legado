package io.legado.app.ui.book.import

/**
 * 导入页平台目录引用: 目录列举与条目化的最小能力面。
 *
 * 状态机主体 (目录栈/面包屑/返回上级/过滤/排序/扫描编排/空态) 在 data/commonMain 的
 * [io.legado.app.ui.book.import.local.ImportBookShared]; 各平台用各自的文件引用包装本接口:
 * Android=FileDoc(SAF) / 桌面=java.io.File / native=okio File。
 */
interface ImportDirRef {

    /** 显示名 (面包屑层级与"返回上级"条目) */
    val displayName: String

    /** 是否目录 */
    val isDirectory: Boolean

    /** 目录持久化标识 (导入页 pref 存取; Android=uri 串 / 桌面与 native=路径) */
    val pathKey: String

    /**
     * 列原始子项 (不过滤, 由状态机统一按书籍/压缩包规则筛)。
     * null = 目录失效不可读 (被删/权限被撤/授权失败)。
     */
    suspend fun listChildren(): List<ImportDirRef>?

    /**
     * 列表条目化 (含"已在书架"判定, 三端口径各自维护: Android/native 按文件名,
     * 桌面按 bookUrl 路径)。[isUpDir] 为 true 时产出"返回上级"条目 (无需文件元数据)。
     * 返回 null = 条目不可读 (文件已消失/属性取不到, native 授权语义)。
     */
    suspend fun toItem(isUpDir: Boolean = false): ImportFileItem?
}

/**
 * 导入页页级平台服务 (目录引用之外的注入点), 供
 * [io.legado.app.ui.book.import.local.ImportBookShared] 消费。
 */
interface ImportBookDirHost {

    /** 列表条目 → 目录引用 (仅目录条目有效; 非目录/非本端条目返回 null) */
    fun dirRefOf(item: ImportFileItem): ImportDirRef?

    /**
     * 按持久化标识解析目录引用 (对照 initRootDoc/initRootPath: 目录失效返回 null)。
     * [pathKey] 为空串时按各端回退语义处理 (Android 回退书籍保存位置, iOS 走
     * bookmark 恢复, 桌面/native 直接判 null)。
     */
    suspend fun resolveRoot(pathKey: String): ImportDirRef?
}
