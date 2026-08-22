package io.legado.app.model

/**
 * 把用户选中的默认封面原图烘焙成指定比例的小图字节 (写盘由调用方负责)。
 *
 * 对照原 app 端 `BookCover.bakeAndWrite`: NOVEL 300×400 居中裁剪 / VIDEO 720×405 顶部裁剪
 * (视频封面保顶部信息), webp q85 编码。actual 只有两份: androidMain (Bitmap) +
 * skikoUiMain 单份 Skiko (jvm/iOS/鸿蒙共用; ImageIO 生态无 webp writer, Skiko 由
 * Compose 各端必带, 同 DesktopImageOps 的 webp 编码路径)。
 *
 * expect 刻意放 commonMain 而非 sharedUiMain (本符号无 UI 依赖): actual↔expect 跨
 * 自定义中间源集配对在 IDE 有误报史 (见 shared/build.gradle.kts androidMain 源集处
 * jvmAndAndroidMain 注释), androidMain↔commonMain 是模板源集间的标准配对, 无此问题。
 *
 * @return 烘焙字节; 解码/编码失败返回 null, 调用方中止添加并提示
 * (解码不了的输入对各端渲染同样是不可解码的, 无回落意义)
 */
internal expect fun bakeDefaultCoverBytes(
    sourceBytes: ByteArray,
    ratio: BookCoverShared.CoverRatio,
): ByteArray?
