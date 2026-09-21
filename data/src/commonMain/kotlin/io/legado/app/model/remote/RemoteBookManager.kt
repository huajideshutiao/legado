package io.legado.app.model.remote

import io.legado.app.data.entities.Book

/**
 * 远程书籍管理抽象类。
 *
 * # 下沉说明 (app → shared/commonMain)
 * - 原 `downloadRemoteBook` 返回 `android.net.Uri`, commonMain 不可用, 改为 `String`
 *   (Uri.toString()); app 端 actual 实现 (RemoteBookWebDav) 内部 String↔Uri 转换,
 *   行为与下沉前一致。不引入 KmpUri expect/actual (此前尝试有问题已删除)。
 */
abstract class RemoteBookManager {

    /**
     * 获取书籍列表
     */
    @Throws(Exception::class)
    abstract suspend fun getRemoteBookList(path: String): MutableList<RemoteBook>

    /**
     * 根据书籍地址获取书籍信息
     */
    @Throws(Exception::class)
    abstract suspend fun getRemoteBook(path: String): RemoteBook?

    /**
     * @return String：下载到本地的路径 (Uri.toString())
     */
    @Throws(Exception::class)
    abstract suspend fun downloadRemoteBook(remoteBook: RemoteBook): String

    /**
     * 上传书籍
     */
    @Throws(Exception::class)
    abstract suspend fun upload(book: Book)

    /**
     * 删除书籍
     */
    @Throws(Exception::class)
    abstract suspend fun delete(remoteBookUrl: String)

}
