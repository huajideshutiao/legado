package io.legado.app.model.analyzeRule

import cn.hutool.crypto.symmetric.SymmetricCrypto
import io.legado.app.constant.AppConst
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapterLike
import io.legado.app.data.entities.BookSource
import io.legado.app.help.JsEncodeUtilsDefaults
import io.legado.app.help.crypto.AsymmetricCrypto
import io.legado.app.help.crypto.AsymmetricCryptoAndroid
import io.legado.app.help.crypto.Sign
import io.legado.app.help.crypto.SignAndroid
import io.legado.app.help.crypto.SymmetricCryptoAndroid
import io.legado.app.model.webBook.BookInfoRefreshers
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.URL
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 桌面端 AnalyzeRule 薄子类: 补齐 app 端 AnalyzeRule JS 面中跨端可用的部分
 * (JsEncodeUtils 摘要/加解密工厂 + refreshTocUrl), 加解密全部复用 shared jvmAndAndroidMain 实现物。
 * jsoup get/head/post 与 startJsActivity 依赖 app 端专属库/组件, 桌面端不提供 (与现状一致)。
 *
 * 放 shared jvmMain 而非 desktop 模块: hutool 在 shared 是 implementation 依赖,
 * desktop 模块编译期不可见, setIv 等 hutool 父类成员只能在 shared 内部引用。
 */
@Suppress("unused")
class DesktopAnalyzeRule(
    ruleData: RuleDataInterface? = null,
    source: BaseSource? = null,
    preUpdateJs: Boolean = false
) : AnalyzeRuleCore(ruleData, source, preUpdateJs), JsEncodeUtilsDefaults {

    /** 恢复原版 java.net.URL 重载路径, 避免 String 门面 substringBefore(",") 截断含逗号的 redirectUrl。 */
    override fun getAbsoluteURL(redirectUrl: URL?, relativePath: String): String {
        return NetworkUtils.getAbsoluteURL(redirectUrl, relativePath)
    }

    /** 与 app 端 AnalyzeRule.refreshTocUrl 同逻辑: 经 [BookInfoRefreshers] 反向调用 WebBook.getBookInfoAwait。 */
    override fun refreshTocUrl() {
        val bookSource = getSource() as? BookSource
        val book = ruleData as? Book
        if (bookSource == null || book == null) return
        val refresher = BookInfoRefreshers.getOrNull() ?: return
        runBlocking(coroutineContext) {
            withTimeout(1800000) {
                refresher.refreshBookInfo(bookSource, book, false)
            }
        }
    }

    // 以下加解密工厂与 app 端 JsEncodeUtilsAndroid 完全同映射 (实现类均在 shared jvmAndAndroidMain)

    fun createSymmetricCrypto(
        transformation: String,
        key: ByteArray?,
        iv: ByteArray?
    ): SymmetricCrypto {
        val symmetricCrypto = SymmetricCryptoAndroid(transformation, key)
        return if (iv != null && iv.isNotEmpty()) symmetricCrypto.setIv(iv) else symmetricCrypto
    }

    fun createSymmetricCrypto(transformation: String, key: ByteArray): SymmetricCrypto {
        return createSymmetricCrypto(transformation, key, null)
    }

    fun createSymmetricCrypto(transformation: String, key: String): SymmetricCrypto {
        return createSymmetricCrypto(transformation, key, null)
    }

    fun createSymmetricCrypto(transformation: String, key: String, iv: String?): SymmetricCrypto {
        return createSymmetricCrypto(
            transformation, key.encodeToByteArray(), iv?.encodeToByteArray()
        )
    }

    fun createAsymmetricCrypto(transformation: String): AsymmetricCrypto {
        return AsymmetricCryptoAndroid(transformation)
    }

    fun createSign(algorithm: String): Sign {
        return SignAndroid(algorithm)
    }
}

/**
 * 桌面端 AnalyzeUrl 薄子类: 与 [DesktopAnalyzeRule] 同理, 补齐 url 内 `<js>` 的
 * java 绑定 JS 面中跨端可用的加解密工厂 (复用 shared jvmAndAndroidMain 实现物)。
 */
@Suppress("unused")
class DesktopAnalyzeUrl(
    rawUrl: String,
    baseUrl: String = "",
    source: BaseSource? = null,
    ruleData: RuleDataInterface? = null,
    chapter: BookChapterLike? = null,
    readTimeout: Long? = null,
    callTimeout: Long? = null,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
    headerMapF: Map<String, String>? = null,
    hasLoginHeader: Boolean = true,
    selectedOptions: Map<String, String>? = null,
    variables: Map<AppConst.JsVarName, Any>? = null
) : AnalyzeUrlCore(
    rawUrl, baseUrl, source, ruleData, chapter, readTimeout, callTimeout,
    coroutineContext, headerMapF, hasLoginHeader, selectedOptions, variables
), JsEncodeUtilsDefaults {

    // 加解密工厂与 DesktopAnalyzeRule 完全同映射 (实现类均在 shared jvmAndAndroidMain)

    fun createSymmetricCrypto(
        transformation: String,
        key: ByteArray?,
        iv: ByteArray?
    ): SymmetricCrypto {
        val symmetricCrypto = SymmetricCryptoAndroid(transformation, key)
        return if (iv != null && iv.isNotEmpty()) symmetricCrypto.setIv(iv) else symmetricCrypto
    }

    fun createSymmetricCrypto(transformation: String, key: ByteArray): SymmetricCrypto {
        return createSymmetricCrypto(transformation, key, null)
    }

    fun createSymmetricCrypto(transformation: String, key: String): SymmetricCrypto {
        return createSymmetricCrypto(transformation, key, null)
    }

    fun createSymmetricCrypto(transformation: String, key: String, iv: String?): SymmetricCrypto {
        return createSymmetricCrypto(
            transformation, key.encodeToByteArray(), iv?.encodeToByteArray()
        )
    }

    fun createAsymmetricCrypto(transformation: String): AsymmetricCrypto {
        return AsymmetricCryptoAndroid(transformation)
    }

    fun createSign(algorithm: String): Sign {
        return SignAndroid(algorithm)
    }
}

/** 桌面端 main 入口注册: shared 编排层创建 AnalyzeRule/AnalyzeUrl 改走桌面薄子类。 */
fun registerDesktopAnalyzeRuleFactory() {
    AnalyzeRuleFactories.register { ruleData, source, preUpdateJs ->
        DesktopAnalyzeRule(ruleData, source, preUpdateJs)
    }
    AnalyzeUrlFactories.register {
            rawUrl, baseUrl, source, ruleData, chapter, readTimeout, callTimeout,
            coroutineContext, headerMapF, hasLoginHeader, selectedOptions, variables ->
        DesktopAnalyzeUrl(
            rawUrl, baseUrl, source, ruleData, chapter, readTimeout, callTimeout,
            coroutineContext, headerMapF, hasLoginHeader, selectedOptions, variables
        )
    }
}
