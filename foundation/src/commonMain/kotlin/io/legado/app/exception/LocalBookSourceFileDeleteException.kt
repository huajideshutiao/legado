package io.legado.app.exception

/**
 * 本地书源文件删除失败: 库里的记录已删, 文件还在磁盘上 (原因见 [message])。
 *
 * 与删记录等其它失败区分开, 上层据此提示书名与原因, 不把别的失败按"删源文件失败"提示。
 */
class LocalBookSourceFileDeleteException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
