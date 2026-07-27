package io.legado.app.utils

/**
 * UUID 生成 expect/actual 门面。
 *
 * commonMain 不能直接引用 java.util.UUID，下沉件需生成随机 UUID 字符串时走本 expect；
 * actual 在 jvmAndAndroidMain（android + jvm 共用 java.util.UUID 实现）。
 */

/** 等价于 java.util.UUID.randomUUID().toString()，返回随机 UUID 字符串。 */
expect fun randomUUIDString(): String
