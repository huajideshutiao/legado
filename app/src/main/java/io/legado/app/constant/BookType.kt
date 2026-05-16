package io.legado.app.constant

import androidx.annotation.IntDef

/**
 * 以二进制位来区分,可能一本书籍包含多个类型,每一位代表一个类型,数值为2的n次方
 * 以二进制位来区分,数据库查询更高效, 数值>=8和老版本类型区分开
 */
@Suppress("ConstPropertyName")
object BookType {
    /**
     * 8 文本
     */
    const val text = 1 shl 3

    /**
     * 16 更新失败
     */
    const val updateError = 1 shl 4

    /**
     * 32 音频
     */
    const val audio = 1 shl 5

    /**
     * 64 图片
     */
    const val image = 1 shl 6

    /**
     * 128 只提供下载服务的网站
     */
    const val webFile = 1 shl 7

    /**
     * 256 本地
     */
    const val local = 1 shl 8

    /**
     * 512 压缩包 表明书籍文件是从压缩包内解压来的
     */
    const val archive = 1 shl 9

    /**
     * 1024 未正式加入到书架的临时阅读书籍
     */
    const val notShelf = 1 shl 10

    const val video = 1 shl 11

    /**
     * 4096 订阅
     */
    const val rss = 1 shl 12

    @Target(AnnotationTarget.VALUE_PARAMETER)
    @Retention(AnnotationRetention.SOURCE)
    @IntDef(
        flag = true,
        value = [text, updateError, audio, image, webFile, local, archive, notShelf, video, rss]
    )
    annotation class Type

    /**
     * 所有可以从书源转换的书籍类型
     */
    const val allBookType = text or image or audio or webFile or video or rss

    const val allBookTypeLocal = text or image or audio or webFile or local or video or rss

    /**
     * 本地书籍书源标志
     */
    const val localTag = "loc_book"

    /**
     * 书源已webDav::开头的书籍,可以从webDav更新或重新下载
     */
    const val webDavTag = "webDav::"

}