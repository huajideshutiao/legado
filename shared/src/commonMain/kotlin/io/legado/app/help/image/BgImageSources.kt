package io.legado.app.help.image

import io.legado.app.utils.encodeURI

/**
 * 内置背景图（bg:// 前缀，午后沙滩等 14 张）的远程下载地址。
 *
 * # 原版逻辑（2026-08-06 用户拍板：恢复远程下载，不打包全图）
 *
 * - 全尺寸原图**不随包分发**（曾打包进 composeResources/files/bg，已撤回，省 ~1MB 包体）；
 *   运行时经 CDN 下载 → 本地缓存（jvm/android 走 RemoteAssetsUtils 缓存，iOS 走 Coil 磁盘缓存）
 * - 远端仓库 master 已删除 `app/src/main/assets/bg/` 目录（删除 commit 8fbede0eab），
 *   jsdelivr `@master` 实测 404；固定到仍含该目录的 commit `de37cc824b`（实测 200）
 * - 缩略图（bg_preview）仍随包分发：shared composeResources `files/bg_preview/`
 *
 * 单一数据源：路径字面量只在此定义（jvm/android 的 RemoteAssetsUtils 与 iOS/鸿蒙加载器共用）。
 */
const val BG_CDN_PATH =
    "huajideshutiao/legado@de37cc824b/app/src/main/assets/bg"

/** 内置背景图 CDN 基址（jsdelivr commit 级地址）。 */
const val BG_CDN_BASE_URL = "https://cdn.jsdelivr.net/gh/$BG_CDN_PATH/"

/** bg:// 文件名 → CDN 下载 URL（中文文件名经 [encodeURI] 编码，与 jvm 下载器同口径）。 */
fun bgCdnUrl(fileName: String): String = BG_CDN_BASE_URL + fileName.encodeURI()
