package io.legado.buildlogic

import java.io.File

/**
 * cinterop 对不完整 typedef (如 typedef struct JSContext JSContext; 仅前向声明) 只生成
 * cnames.structs.* 包别名, 顶层类型名靠这里生成的 typealias 补齐。iOS/鸿蒙各 stage 任务共用。
 *
 * 独立为 build-logic 函数: 任务动作 (doLast) 在配置缓存下不得分发脚本方法/引用脚本对象,
 * 仅允许访问 build-logic 类路径上的静态函数。
 */
fun generateCNamesAliases(outputRoot: File) {
    val quickJsAliases = outputRoot.resolve(
        "io/legado/app/napi/quickjs/CNamesAliases.kt"
    )
    quickJsAliases.parentFile.mkdirs()
    quickJsAliases.writeText(
        """
        @file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

        package io.legado.app.napi.quickjs

        typealias JSContext = cnames.structs.JSContext
        typealias JSRuntime = cnames.structs.JSRuntime
        """.trimIndent() + "\n"
    )
    val mbedTlsAliases = outputRoot.resolve(
        "io/legado/app/nativecrypto/mbedtls/CNamesAliases.kt"
    )
    mbedTlsAliases.parentFile.mkdirs()
    mbedTlsAliases.writeText(
        """
        @file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

        package io.legado.app.nativecrypto.mbedtls

        typealias mbedtls_md_info_t = cnames.structs.mbedtls_md_info_t
        """.trimIndent() + "\n"
    )
}
