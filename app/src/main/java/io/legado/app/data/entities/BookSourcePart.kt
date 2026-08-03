package io.legado.app.data.entities

import io.legado.app.data.appDb
import kotlinx.coroutines.runBlocking

/*
 * BookSourcePart data class 已下沉 shared jvmAndAndroidMain (同包名跨模块自动合并);
 * 本文件仅保留依赖 app 端 appDb 的扩展函数 (resolveBookSource/toBookSource)。
 */

fun BookSourcePart.resolveBookSource(): BookSource? {
    return runBlocking { appDb.bookSourceDao.getBookSource(bookSourceUrl) }
}

fun List<BookSourcePart>.toBookSource(): List<BookSource> {
    return runBlocking { appDb.bookSourceDao.getBookSourcesFix(map { it.bookSourceUrl }) }
}
