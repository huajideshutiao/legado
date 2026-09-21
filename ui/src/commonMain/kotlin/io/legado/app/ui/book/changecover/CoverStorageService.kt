package io.legado.app.ui.book.changecover

/**
 * 封面持久化平台桥 (KMP 注入点, 只服务"选本地图片作封面"场景)。
 *
 * # 背景
 *
 * Android 端 pickFile 会把 SAF 选择的文件物化到 `cacheDir/file_picker` (缓存目录,
 * 可被系统清理); 原版 BookInfoEditActivity.coverChangeTo(uri) 则是把选中的图片复制到
 * `externalFilesDir/covers/<md5(内容)>.<ext>` 持久保存, 同名 (同内容) 文件复用。
 * 本接口把"复制到持久目录"抽象成平台桥, 经 [CoverStorageServiceProviders] 注入,
 * 不污染通用 pickFile 行为 (方案 A: 独立服务只服务封面场景)。
 *
 * # 各端实现
 *
 * - **Android**: `customImg/covers/<字节数>.<ext>` (内容特征值命名, 同字节数复用),
 *   复制成功后顺带清理 file_picker 临时物化文件 (仅限缓存目录内); 烘焙产物写缓存目录。
 * - **桌面**: `customImg/covers/<字节数>.<ext>` (与 Android 同规则)。
 * - **iOS/ohos**: 未注册, 走 [CoverStorageServiceProviders] 默认实现返回 null,
 *   调用方回退使用 pickFile 原路径 (与引入本桥前的行为一致)。
 *
 * @param srcPath pickFile 返回的已物化本地路径
 * @param displayName 展示文件名 (仅用于取扩展名, 对齐原版 `fileDoc.name.substringAfterLast(".")`)
 * @return 持久化后的**图集内部相对引用** (如 `covers/<字节数>.<ext>`, 由
 *   [io.legado.app.help.config.resolveImagePath] 解析到图集目录); 失败返回 null (调用方回退 srcPath)。
 *   存相对引用而非绝对路径是为了跨机/跨端恢复备份后仍有效 —— 图集目录随备份 zip 落到新文件根,
 *   绝对路径必然指向旧机。
 */
interface CoverStorageService {

    /** 把选中的图片文件复制到持久目录, 返回持久路径; 失败返回 null。 */
    fun persistCover(srcPath: String, displayName: String): String?
}
