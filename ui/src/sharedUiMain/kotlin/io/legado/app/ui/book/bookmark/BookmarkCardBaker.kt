package io.legado.app.ui.book.bookmark

import androidx.compose.ui.graphics.ImageBitmap

/**
 * 分享卡片位图 → PNG 字节 (无损)。
 *
 * expect 落在 sharedUiMain (签名引用 compose ImageBitmap, commonMain 无 Compose 依赖),
 * actual 两份, 模式对照 WallpaperBaker / DefaultCoverBaker (expect in commonMain 系
 * 同族先例, 本功能签名含 Compose 类型故下沉一档):
 * - androidMain: Bitmap.compress (PNG)
 * - skikoUiMain (jvm/iOS/鸿蒙 三端共用): skia Image.encodeToData (PNG), 同
 *   NativeBookArchiveExport 的 native PNG 编码路径
 *
 * 编码失败抛异常 (调用方记 AppLog), 不吞错。
 */
internal expect fun ImageBitmap.encodePngBytes(): ByteArray
