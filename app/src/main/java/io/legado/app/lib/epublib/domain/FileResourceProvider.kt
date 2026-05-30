package io.legado.app.lib.epublib.domain

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * 用于创建epub，添加大文件（如大量图片）时容易OOM，使用LazyResource，避免OOM.
 */
class FileResourceProvider : LazyResourceProvider {
    //需要导入资源的父目录
    var dir: String?

    /**
     * 创建一个文件夹里面文件夹的LazyResourceProvider，用于LazyResource。
     * 
     * @param parentDir 文件的目录
     */
    constructor(parentDir: String?) {
        this.dir = parentDir
    }

    /**
     * 创建一个文件夹里面文件夹的LazyResourceProvider，用于LazyResource。
     * 
     * @param parentFile 文件夹
     */
    @Suppress("unused")
    constructor(parentFile: File) {
        this.dir = parentFile.getPath()
    }

    /**
     * 根据子文件名href,再父目录下读取文件获取FileInputStream
     * 
     * @param href 子文件名href
     * @return 对应href的FileInputStream
     * @throws IOException 抛出IOException
     */
    @Throws(IOException::class)
    override fun getResourceStream(href: String?): InputStream? {
        if (href == null) return null
        return FileInputStream(File(dir, href))
    }
}
