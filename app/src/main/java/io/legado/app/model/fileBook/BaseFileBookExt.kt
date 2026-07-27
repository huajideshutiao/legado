package io.legado.app.model.fileBook

import io.legado.app.data.entities.Book
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * [BaseFileBook] 的 Android 平台扩展函数 (app 端)。
 *
 * 原 app 端 `interface BaseFileBook` 含 `getBookInputStream` / `getLastModified`
 * 默认实现, 重 Android 依赖 (appCtx / DocumentFile / importFromArchive /
 * downloadRemoteBook), 不下沉到 commonMain (commonMain [BaseFileBook] 仅保留 5 个
 * 核心解析方法)。
 *
 * 下沉后这两个方法的真实实现移到 [FileBookAccessorImpl] (经 [FileBookAccessor]
 * 暴露), 本扩展仅作签名保留, 委托 accessor, 让 app 端调用方
 * (`FileBook.getBookInputStream(book)`) 调用语法不变, Kotlin 自动解析扩展函数。
 *
 * # 调用点
 * - [io.legado.app.help.book.BookHelp.kt] 第 311 行: `FileBook.getBookInputStream(book)`
 * - [io.legado.app.ui.book.read.ReadBookViewModel.kt] 第 178 行: `FileBook.getBookInputStream(book)`
 *
 * # @Throws 注解
 * 原 BaseFileBook.getBookInputStream 标注 `@Throws(FileNotFoundException, SecurityException)`,
 * 实际异常由 [FileBookAccessorImpl.getBookInputStream] 抛出, 本扩展透传该注解
 * 以保持 Java 调用方 checked exception 处理行为不变。
 */
@Throws(FileNotFoundException::class, SecurityException::class)
fun BaseFileBook.getBookInputStream(book: Book): InputStream =
    FileBookProviders.get().getBookInputStream(book)

/**
 * 获取书籍文件最后修改时间 (对应原 BaseFileBook.getLastModified 默认实现)。
 *
 * 委托 [FileBookAccessor.getLastModified], 行为一致。
 *
 * @return Result.success(millis) 成功; Result.failure 文件不存在或异常
 */
fun BaseFileBook.getLastModified(book: Book): Result<Long> =
    FileBookProviders.get().getLastModified(book)
