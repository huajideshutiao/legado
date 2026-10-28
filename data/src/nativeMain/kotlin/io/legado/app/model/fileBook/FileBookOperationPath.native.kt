package io.legado.app.model.fileBook

import kotlin.native.concurrent.ThreadLocal

/**
 * 本次协调访问的实际文件路径。
 *
 * 只作为“打开文件用哪个地址”的旁路: 书籍身份仍是 [io.legado.app.data.entities.Book.bookUrl],
 * 解析器/删除原语在同步调用栈内读该值, 不把协调地址写回书籍引用或缓存键。
 * 按线程隔离: 协调块内是同步调用, 不同请求互不影响。
 */
@ThreadLocal
internal object FileBookOperationPath {

    private var path: String? = null

    fun current(): String? = path

    fun <T> withPath(value: String, block: () -> T): T {
        val previous = path
        path = value
        try {
            return block()
        } finally {
            path = previous
        }
    }
}
