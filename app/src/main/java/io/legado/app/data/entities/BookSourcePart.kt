package io.legado.app.data.entities

import io.legado.app.data.appDb
import kotlinx.coroutines.runBlocking

/*
 * K5-c Phase 2: BookSourcePart data class 已下沉 shared jvmAndAndroidMain (同包名跨模块自动合并)。
 * 本文件仅保留依赖 app 端 appDb 的扩展函数 (getBookSource/toBookSource), 无法下沉 shared。
 * 跨模块同包名同签名扩展自动合并, 消费方 import 零改动。
 */

fun BookSourcePart.getBookSource(): BookSource? {
    return runBlocking { appDb.bookSourceDao.getBookSource(bookSourceUrl) }
}

fun List<BookSourcePart>.toBookSource(): List<BookSource> {
    return runBlocking { appDb.bookSourceDao.getBookSourcesFix(map { it.bookSourceUrl }) }
}
