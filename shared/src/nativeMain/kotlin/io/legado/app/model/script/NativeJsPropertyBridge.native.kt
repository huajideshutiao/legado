@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.model.script

import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import io.legado.app.data.entities.BaseBook
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookChapterLike
import io.legado.app.data.entities.BookSource
import io.legado.app.help.source.SourceCacheProvider
import io.legado.app.help.source.SourceNetworkProvider
import io.legado.app.model.analyzeRule.AnalyzeRuleCore
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import io.legado.app.napi.quickjs.JSContext
import io.legado.app.napi.quickjs.JSValue
import io.legado.app.napi.quickjs.JS_NewArray
import io.legado.app.napi.quickjs.JS_NewObject
import io.legado.app.napi.quickjs.JS_SetPropertyStr
import io.legado.app.napi.quickjs.JS_SetPropertyUint32
import io.legado.app.napi.quickjs.JS_TAG_NULL
import io.legado.app.napi.quickjs.JS_TAG_UNDEFINED
import io.legado.app.napi.quickjs.qjs_NewBool
import io.legado.app.napi.quickjs.qjs_NewFloat64
import io.legado.app.napi.quickjs.qjs_NewInt32
import io.legado.app.napi.quickjs.qjs_NewString
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.cValue

/**
 * native 端 (iOS/鸿蒙) 复杂对象属性桥: 手写静态属性分派表 (路线 C)。
 *
 * # 背景
 * [NativeJsEngine.toJsValue] 只桥接 JsExtensionsCommon, `book`/`chapter` 等 binding
 * 注入即 undefined; JVM 端这些属性靠 JavaObjectBridge 反射 findGetter 兜底
 * (Book/BookChapter 无 @JsApi 注解, KSP 分派表不覆盖), native 无反射, 只能手写静态表。
 *
 * # 方案
 * - propertyId 段 1700-2199 (避开 NativeJsExtensionsBridge 的 1-699 / 1000-1699):
 *   1700-1799 BaseBook (book) | 1800-1899 BookChapter (chapter) | 1900-1999 BookSource |
 *   2000-2099 BaseSource (source) | 2100-2199 AnalyzeUrlCore/AnalyzeRuleCore (java)
 * - 2200+ 带参分派 (经 [dispatchWithArgs], 由 NativeJsExtensionsBridge 解析参数后转发):
 *   2300-2399 BaseBook 变量/方法面 (getVariable/putVariable 等) | 2400-2499 BookChapter 变量/方法面 |
 *   2500-2599 cookie (SourceNetworkProvider) | 2600-2699 cache (SourceCacheProvider) |
 *   2700-3199 属性写 (setterId = getterId + 1000, 对应 1700-2199 的 var 字段) |
 *   3200-3299 ksoup Element/Node 方法面 (src binding 逐项循环场景)
 *   注: 属性名→propId 在 JS 工厂静态绑定; 读/写经同一对象同一 propId 语义, setter 仅偏移 +1000,
 *   与 JVM 端 JavaObjectBridge 的 findGetter/findSetter (bean 属性读写) 对齐。
 * - JS 侧由工厂函数 (__createBookObj 等) 用 Object.defineProperty 挂 getter/setter,
 *   与 JsURL 属性段 (1500-1599) 同一模式; getter 调 `__nativeDispatch(handle, propId, [])`、
 *   setter 调 `__nativeDispatch(handle, propId + 1000, [v])` 回 Kotlin。
 * - 语义对齐 JVM 端 JsApiDispatcher.getProperty(target, name): miss 返回 null
 *   (调用方转 undefined, 等价 NO_MATCH); 写 miss 静默 (与 JVM 端 setter 反射找不到时一致)。
 * - 嵌套对象 (AnalyzeRuleCore.chapter/ruleData): getter 返回 handle, JS 侧工厂函数包装
 *   (与 getSource/queryTTF 同模式; handle 不入 scope.handles, 泄漏量每次访问一个, 同已知取舍)。
 *
 * # hot path
 * 一次属性读取: 1 次 handle 表 HashMap get + 1 次 when(propId) 整数跳转 +
 * 字段直读 + 1 次 JSValue 构造。零堆分配 (bridge.dispatch 对 >= 1700 短路, 跳过参数数组解析)。
 */
const val PROPERTY_ID_BASE = 1700

/** 带参分派起始 (属性写/变量方法/cookie/cache, 经 [NativeJsPropertyBridge.dispatchWithArgs])。 */
const val PROPERTY_WRITE_BASE = 2200

object NativeJsPropertyBridge {

    /**
     * 属性分派: propId → 对象字段。未命中返回 null (调用方转 JS undefined)。
     *
     * 注: when(propId) 是整数跳转表 (table switch), 与 JVM 端反射 getter 相比
     * 无方法查找/无字符串比较; is 类型检查在工厂创建时已保证 (getter 只挂在对应类型对象上),
     * 此处 as? 仅为防御。
     */
    fun dispatch(ctx: CPointer<JSContext>, obj: Any, propId: Int): CValue<JSValue>? = when (propId) {
        // ============ BaseBook 属性 (1700-1799, book binding) ============
        1701 -> (obj as? BaseBook)?.let { stringToJsValue(ctx, it.bookUrl) } // bookUrl
        1702 -> (obj as? BaseBook)?.let { stringToJsValue(ctx, it.name) } // name
        1703 -> (obj as? BaseBook)?.let { stringToJsValue(ctx, it.author) } // author
        1704 -> (obj as? BaseBook)?.let { stringToJsValue(ctx, it.origin) } // origin
        1705 -> (obj as? BaseBook)?.let { stringToJsValue(ctx, it.originName) } // originName
        1706 -> (obj as? BaseBook)?.let { stringToJsValue(ctx, it.kind) } // kind
        1707 -> (obj as? BaseBook)?.let { stringToJsValue(ctx, it.wordCount) } // wordCount
        1708 -> (obj as? BaseBook)?.let { stringToJsValue(ctx, it.variable) } // variable
        1709 -> (obj as? BaseBook)?.let { stringToJsValue(ctx, it.infoHtml) } // infoHtml
        1710 -> (obj as? BaseBook)?.let { stringToJsValue(ctx, it.tocHtml) } // tocHtml
        1711 -> (obj as? BaseBook)?.let { qjs_NewInt32(ctx, it.type) } // type
        1712 -> (obj as? BaseBook)?.let { stringToJsValue(ctx, it.coverUrl) } // coverUrl
        1713 -> (obj as? BaseBook)?.let { stringToJsValue(ctx, it.intro) } // intro (注意不是 introduction)
        1714 -> (obj as? BaseBook)?.let { stringToJsValue(ctx, it.latestChapterTitle) } // latestChapterTitle
        1715 -> (obj as? BaseBook)?.let { stringToJsValue(ctx, it.tocUrl) } // tocUrl
        1716 -> (obj as? BaseBook)?.let { qjs_NewInt32(ctx, it.originOrder) } // originOrder

        // ============ Book 实体属性 (1717-1734, book binding) ============
        // 注: BaseBook 接口仅 15 个属性, Book 实体专属字段用 as? Book (与 JVM 反射实际类一致)
        1717 -> (obj as? Book)?.let { stringToJsValue(ctx, it.customTag) } // customTag
        1718 -> (obj as? Book)?.let { stringToJsValue(ctx, it.customCoverUrl) } // customCoverUrl
        1719 -> (obj as? Book)?.let { stringToJsValue(ctx, it.customIntro) } // customIntro
        1720 -> (obj as? Book)?.let { stringToJsValue(ctx, it.charset) } // charset
        1721 -> (obj as? Book)?.let { longToJsValue(ctx, it.group) } // group
        1722 -> (obj as? Book)?.let { longToJsValue(ctx, it.latestChapterTime) } // latestChapterTime
        1723 -> (obj as? Book)?.let { longToJsValue(ctx, it.lastCheckTime) } // lastCheckTime
        1724 -> (obj as? Book)?.let { qjs_NewInt32(ctx, it.lastCheckCount) } // lastCheckCount
        1725 -> (obj as? Book)?.let { qjs_NewInt32(ctx, it.totalChapterNum) } // totalChapterNum
        1726 -> (obj as? Book)?.let { stringToJsValue(ctx, it.durChapterTitle) } // durChapterTitle
        1727 -> (obj as? Book)?.let { qjs_NewInt32(ctx, it.durChapterIndex) } // durChapterIndex
        1728 -> (obj as? Book)?.let { qjs_NewInt32(ctx, it.durChapterPos) } // durChapterPos
        1729 -> (obj as? Book)?.let { longToJsValue(ctx, it.durChapterTime) } // durChapterTime
        1730 -> (obj as? Book)?.let { qjs_NewBool(ctx, if (it.canUpdate) 1 else 0) } // canUpdate
        1731 -> (obj as? Book)?.let { qjs_NewInt32(ctx, it.order) } // order
        1732 -> (obj as? Book)?.let { longToJsValue(ctx, it.syncTime) } // syncTime
        1733 -> (obj as? Book)?.let { stringListToJsArray(ctx, it.downloadUrls ?: emptyList()) } // downloadUrls
        1734 -> (obj as? Book)?.let { qjs_NewInt32(ctx, it.lastChapterIndex) } // lastChapterIndex (val, 只读)

        // ============ BookChapter 属性 (1800-1899, chapter binding) ============
        1801 -> (obj as? BookChapter)?.let { stringToJsValue(ctx, it.url) } // url
        1802 -> (obj as? BookChapterLike)?.let { stringToJsValue(ctx, it.title) } // title
        1803 -> (obj as? BookChapter)?.let { stringToJsValue(ctx, it.bookUrl) } // bookUrl
        1804 -> (obj as? BookChapter)?.let { qjs_NewInt32(ctx, it.index) } // index
        1805 -> (obj as? BookChapter)?.let { qjs_NewBool(ctx, if (it.isVolume) 1 else 0) } // isVolume
        1806 -> (obj as? BookChapter)?.let { qjs_NewBool(ctx, if (it.isVip) 1 else 0) } // isVip
        1807 -> (obj as? BookChapter)?.let { qjs_NewBool(ctx, if (it.isPay) 1 else 0) } // isPay
        1808 -> (obj as? BookChapter)?.let { stringToJsValue(ctx, it.resourceUrl) } // resourceUrl
        1809 -> (obj as? BookChapter)?.let { stringToJsValue(ctx, it.tag) } // tag
        1810 -> (obj as? BookChapter)?.let { stringToJsValue(ctx, it.wordCount) } // wordCount
        1811 -> (obj as? BookChapter)?.let { longToJsValue(ctx, it.start) } // start
        1812 -> (obj as? BookChapter)?.let { longToJsValue(ctx, it.end) } // end
        1813 -> (obj as? BookChapter)?.let { stringToJsValue(ctx, it.variable) } // variable
        1814 -> (obj as? BookChapter)?.let { stringToJsValue(ctx, it.startFragmentId) } // startFragmentId
        1815 -> (obj as? BookChapter)?.let { stringToJsValue(ctx, it.endFragmentId) } // endFragmentId
        1816 -> (obj as? BookChapter)?.let { stringToJsValue(ctx, it.titleMD5) } // titleMD5 (@Transient)

        // ============ BookSource 属性 (1900-1999, source binding 的 BookSource 面) ============
        1901 -> (obj as? BookSource)?.let { stringToJsValue(ctx, it.bookSourceUrl) } // bookSourceUrl
        1902 -> (obj as? BookSource)?.let { stringToJsValue(ctx, it.bookSourceName) } // bookSourceName
        1903 -> (obj as? BookSource)?.let { stringToJsValue(ctx, it.bookSourceGroup) } // bookSourceGroup
        1904 -> (obj as? BookSource)?.let { qjs_NewInt32(ctx, it.bookSourceType) } // bookSourceType
        1905 -> (obj as? BookSource)?.let { stringToJsValue(ctx, it.bookUrlPattern) } // bookUrlPattern
        1906 -> (obj as? BookSource)?.let { qjs_NewInt32(ctx, it.customOrder) } // customOrder
        1907 -> (obj as? BookSource)?.let { qjs_NewBool(ctx, if (it.enabled) 1 else 0) } // enabled
        1908 -> (obj as? BookSource)?.let { qjs_NewBool(ctx, if (it.enabledExplore) 1 else 0) } // enabledExplore

        // ============ BookSource 实体属性 (1909-1926, source binding 的 BookSource 面) ============
        1909 -> (obj as? BookSource)?.let { qjs_NewBool(ctx, if (it.enabledReview) 1 else 0) } // enabledReview
        1910 -> (obj as? BookSource)?.let { stringToJsValue(ctx, it.loginCheckJs) } // loginCheckJs
        1911 -> (obj as? BookSource)?.let { stringToJsValue(ctx, it.coverDecodeJs) } // coverDecodeJs
        1912 -> (obj as? BookSource)?.let { stringToJsValue(ctx, it.bookSourceComment) } // bookSourceComment
        1913 -> (obj as? BookSource)?.let { stringToJsValue(ctx, it.variableComment) } // variableComment
        1914 -> (obj as? BookSource)?.let { longToJsValue(ctx, it.lastUpdateTime) } // lastUpdateTime
        1915 -> (obj as? BookSource)?.let { longToJsValue(ctx, it.respondTime) } // respondTime
        1916 -> (obj as? BookSource)?.let { qjs_NewInt32(ctx, it.weight) } // weight
        1917 -> (obj as? BookSource)?.let { stringToJsValue(ctx, it.exploreUrl) } // exploreUrl
        1918 -> (obj as? BookSource)?.let { stringToJsValue(ctx, it.exploreScreen) } // exploreScreen
        1919 -> (obj as? BookSource)?.let { qjs_NewInt32(ctx, it.exploreStyle) } // exploreStyle
        1920 -> (obj as? BookSource)?.let { stringToJsValue(ctx, it.ruleExplore) } // ruleExplore
        1921 -> (obj as? BookSource)?.let { stringToJsValue(ctx, it.searchUrl) } // searchUrl
        1922 -> (obj as? BookSource)?.let { stringToJsValue(ctx, it.ruleSearch) } // ruleSearch
        1923 -> (obj as? BookSource)?.let { stringToJsValue(ctx, it.ruleBookInfo) } // ruleBookInfo
        1924 -> (obj as? BookSource)?.let { stringToJsValue(ctx, it.ruleToc) } // ruleToc
        1925 -> (obj as? BookSource)?.let { stringToJsValue(ctx, it.ruleContent) } // ruleContent
        1926 -> (obj as? BookSource)?.let { stringToJsValue(ctx, it.ruleReview) } // ruleReview

        // ============ BookSource 复杂对象属性 (搜索/详情/目录规则对象等, 嵌套对象无法桥接) ============
        // 1927 searchRule / 1928 exploreRule / 1929 bookInfoRule / 1930 tocRule / 1931 contentRule /
        // 1932 reviewRule: JVM 端反射返回 SearchRule 等嵌套对象, native 无嵌套对象桥, 保持 undefined
        // (两端不一致, 见报告"未覆盖: 嵌套复杂对象")
        // ============ BaseSource 属性 (2000-2099, source binding) ============
        2001 -> (obj as? BaseSource)?.let { stringToJsValue(ctx, it.concurrentRate) } // concurrentRate
        2002 -> (obj as? BaseSource)?.let { stringToJsValue(ctx, it.loginUrl) } // loginUrl
        2003 -> (obj as? BaseSource)?.let { stringToJsValue(ctx, it.loginUi) } // loginUi
        2004 -> (obj as? BaseSource)?.let { stringToJsValue(ctx, it.header) } // header
        2005 -> (obj as? BaseSource)?.let { boolOrNullToJsValue(ctx, it.enabledCookieJar) } // enabledCookieJar
        2006 -> (obj as? BaseSource)?.let { boolOrNullToJsValue(ctx, it.enableDangerousApi) } // enableDangerousApi
        2007 -> (obj as? BaseSource)?.let { stringToJsValue(ctx, it.jsLib) } // jsLib

        // ============ AnalyzeUrlCore 属性 (2100-2199, java binding) ============
        2101 -> (obj as? AnalyzeUrlCore)?.let { stringToJsValue(ctx, it.rawUrl) } // rawUrl
        2102 -> (obj as? AnalyzeUrlCore)?.let { stringToJsValue(ctx, it.urlNoQuery) } // urlNoQuery
        2103 -> (obj as? AnalyzeUrlCore)?.let { stringToJsValue(ctx, it.type) } // type
        2104 -> (obj as? AnalyzeUrlCore)?.let { longToJsValue(ctx, it.serverID) } // serverID
        2105 -> (obj as? AnalyzeUrlCore)?.let { stringToJsValue(ctx, GSON.toJson(it.headerMap)) } // headerMap → JSON (JS 侧 JSON.parse)
        2106 -> (obj as? AnalyzeUrlCore)?.let { stringToJsValue(ctx, it.url) } // url
        2107 -> (obj as? AnalyzeUrlCore)?.let { stringToJsValue(ctx, it.urlAfterJs) } // urlAfterJs
        2108 -> (obj as? AnalyzeUrlCore)?.let { stringToJsValue(ctx, it.encodedParams) } // encodedParams

        // ============ AnalyzeRuleCore 属性 (2109-2112, java binding 的 AnalyzeRule 面) ============
        // 对齐 JVM 端反射可见的 public var (chapter/nextChapterUrl/variables/ruleData);
        // chapter/ruleData 返回 handle, 由 JS 工厂包装为嵌套对象 (与 getSource 同模式)
        2109 -> (obj as? AnalyzeRuleCore)?.chapter?.let {
            qjs_NewFloat64(ctx, NativeJsExtensionsBridge.registerObject(it).toDouble())
        } // chapter (嵌套 handle)
        2110 -> (obj as? AnalyzeRuleCore)?.let { stringToJsValue(ctx, it.nextChapterUrl) } // nextChapterUrl
        2111 -> (obj as? AnalyzeRuleCore)?.variables?.let { anyToJs(ctx, it) } ?: jsNull() // variables (Map)
        2112 -> (obj as? AnalyzeRuleCore)?.ruleData?.let {
            qjs_NewFloat64(ctx, NativeJsExtensionsBridge.registerObject(it).toDouble())
        } // ruleData (嵌套 handle)

        else -> null // 未知属性 → undefined (等价 JsDispatchRegistry.NO_MATCH)
    }

    /**
     * 带参分派 (methodId >= [PROPERTY_WRITE_BASE], 由 NativeJsExtensionsBridge 解析参数后转发):
     *
     * - 2300-2399 BaseBook 变量/方法面: getVariable/putVariable/putBigVariable/getBigVariable /
     *   putCustomVariable/getCustomVariable/getKindList/getRealAuthor
     *   (对齐 JVM 端反射可调用的 RuleDataInterface 接口默认方法 + Book 方法)
     * - 2400-2499 BookChapter 变量/方法面: getVariable/putVariable/putBigVariable/getBigVariable /
     *   primaryStr/getFileName/getFontName
     * - 2500-2599 cookie: SourceNetworkProvider.getCookie/replaceCookie/removeCookie
     * - 2600-2699 cache: SourceCacheProvider.get/put/delete/getFromMemory/putMemory/deleteMemory /
     *   put(saveTime)/getInt/getLong/getDouble/getFloat/clearMemoryByPrefixes
     * - 2700-3199 属性写: setterId = getterId + 1000, 值转换宽松对齐 JVM 端 coerceValue
     *   (Number/String → Int/Long, 其余类型/null 不写, 与 setter 反射找不到时静默一致)
     *
     * @param args 已由调用方解析的 Kotlin 参数列表 (JS Array → List<Any?>, 基本类型直转)
     * @return 返回值 JSValue, 或 null (未命中/类型不符, 调用方转 undefined)
     */
    fun dispatchWithArgs(
        ctx: CPointer<JSContext>,
        obj: Any,
        methodId: Int,
        args: List<Any?>
    ): CValue<JSValue>? = when {
        // ============ BaseBook 变量/方法面 (2300-2399, book binding) ============
        methodId == 2301 -> (obj as? BaseBook)?.let {
            stringToJsValue(ctx, it.getVariable(args.getString(0)))
        } // getVariable(key)
        methodId == 2302 -> (obj as? BaseBook)?.let {
            qjs_NewBool(ctx, if (it.putVariable(args.getString(0), toNullableString(args.getOrNull(1)))) 1 else 0)
        } // putVariable(key, value) → Boolean
        methodId == 2303 -> (obj as? BaseBook)?.let {
            it.putBigVariable(args.getString(0), toNullableString(args.getOrNull(1)))
            jsUndefined()
        } // putBigVariable(key, value)
        methodId == 2304 -> (obj as? BaseBook)?.let {
            stringToJsValue(ctx, it.getBigVariable(args.getString(0)))
        } // getBigVariable(key) → String?
        methodId == 2305 -> (obj as? BaseBook)?.let {
            it.putCustomVariable(toNullableString(args.getOrNull(0)))
            jsUndefined()
        } // putCustomVariable(value)
        methodId == 2306 -> (obj as? BaseBook)?.let {
            stringToJsValue(ctx, it.getCustomVariable())
        } // getCustomVariable()
        methodId == 2307 -> (obj as? BaseBook)?.let {
            stringListToJsArray(ctx, it.getKindList())
        } // getKindList()
        methodId == 2308 -> (obj as? BaseBook)?.let {
            stringToJsValue(ctx, it.getRealAuthor())
        } // getRealAuthor()

        // ============ BookChapter 变量/方法面 (2400-2499, chapter binding) ============
        methodId == 2401 -> (obj as? BookChapterLike)?.let {
            stringToJsValue(ctx, it.getVariable(args.getString(0)))
        } // getVariable(key)
        methodId == 2402 -> (obj as? BookChapterLike)?.let {
            qjs_NewBool(ctx, if (it.putVariable(args.getString(0), toNullableString(args.getOrNull(1)))) 1 else 0)
        } // putVariable(key, value) → Boolean
        methodId == 2403 -> (obj as? BookChapterLike)?.let {
            it.putBigVariable(args.getString(0), toNullableString(args.getOrNull(1)))
            jsUndefined()
        } // putBigVariable(key, value)
        methodId == 2404 -> (obj as? BookChapterLike)?.let {
            stringToJsValue(ctx, it.getBigVariable(args.getString(0)))
        } // getBigVariable(key) → String?
        methodId == 2405 -> (obj as? BookChapter)?.let { stringToJsValue(ctx, it.primaryStr()) } // primaryStr()
        methodId == 2406 -> (obj as? BookChapter)?.let { stringToJsValue(ctx, it.getFileName()) } // getFileName() (默认 nb)
        methodId == 2407 -> (obj as? BookChapter)?.let { stringToJsValue(ctx, it.getFontName()) } // getFontName()

        // ============ cookie (2500-2599, SourceNetworkProvider) ============
        methodId == 2501 -> (obj as? SourceNetworkProvider)?.let {
            stringToJsValue(ctx, it.getCookie(args.getString(0)))
        } // getCookie(tag)
        methodId == 2502 -> (obj as? SourceNetworkProvider)?.let {
            it.replaceCookie(args.getString(0), args.getString(1))
            jsUndefined()
        } // replaceCookie(tag, cookie)
        methodId == 2503 -> (obj as? SourceNetworkProvider)?.let {
            it.removeCookie(args.getString(0))
            jsUndefined()
        } // removeCookie(tag)

        // ============ cache (2600-2699, SourceCacheProvider) ============
        methodId == 2601 -> (obj as? SourceCacheProvider)?.let {
            stringToJsValue(ctx, it.get(args.getString(0)))
        } // get(key) → String?
        methodId == 2602 -> (obj as? SourceCacheProvider)?.let {
            it.put(args.getString(0), args.getString(1))
            jsUndefined()
        } // put(key, value)
        methodId == 2603 -> (obj as? SourceCacheProvider)?.let {
            it.delete(args.getString(0))
            jsUndefined()
        } // delete(key)
        methodId == 2604 -> (obj as? SourceCacheProvider)?.let {
            anyToJs(ctx, it.getFromMemory(args.getString(0)))
        } // getFromMemory(key) → Any?
        methodId == 2605 -> (obj as? SourceCacheProvider)?.let {
            args.getOrNull(1)?.let { v -> it.putMemory(args.getString(0), v) }
            jsUndefined()
        } // putMemory(key, value)
        methodId == 2606 -> (obj as? SourceCacheProvider)?.let {
            it.deleteMemory(args.getString(0))
            jsUndefined()
        } // deleteMemory(key)
        methodId == 2607 -> (obj as? SourceCacheProvider)?.let {
            it.put(args.getString(0), args.getString(1), (args.getOrNull(2) as? Number)?.toInt() ?: 0)
            jsUndefined()
        } // put(key, value, saveTime)
        methodId == 2608 -> (obj as? SourceCacheProvider)?.let {
            it.getInt(args.getString(0))?.let { v -> qjs_NewInt32(ctx, v) } ?: jsNull()
        } // getInt(key) → Int?
        methodId == 2609 -> (obj as? SourceCacheProvider)?.let {
            it.getLong(args.getString(0))?.let { v -> qjs_NewFloat64(ctx, v.toDouble()) } ?: jsNull()
        } // getLong(key) → Long?
        methodId == 2610 -> (obj as? SourceCacheProvider)?.let {
            it.getDouble(args.getString(0))?.let { v -> qjs_NewFloat64(ctx, v) } ?: jsNull()
        } // getDouble(key) → Double?
        methodId == 2611 -> (obj as? SourceCacheProvider)?.let {
            it.getFloat(args.getString(0))?.let { v -> qjs_NewFloat64(ctx, v.toDouble()) } ?: jsNull()
        } // getFloat(key) → Float?
        methodId == 2612 -> (obj as? SourceCacheProvider)?.let {
            val prefixes = (args.getOrNull(0) as? List<*>)
                ?.map { p -> p?.toString() ?: "" } ?: emptyList()
            it.clearMemoryByPrefixes(prefixes)
            jsUndefined()
        } // clearMemoryByPrefixes(prefixes)

        // ============ 属性写 (2700-3199, setterId = getterId + 1000) ============
        // 值转换: 非空字段 null/类型不符 → 跳过不写 (对齐 JVM 端 findSetter 找不到时静默);
        // 可空字段 → null 直写 (对齐 JVM 端 null 参数反射调用)。
        // ============ BaseBook 面 (2701-2716) ============
        methodId == 2701 -> (obj as? BaseBook)?.let { b ->
            toNullableString(args.getOrNull(0))?.let { b.bookUrl = it }
            jsUndefined()
        }
        methodId == 2702 -> (obj as? BaseBook)?.let { b ->
            toNullableString(args.getOrNull(0))?.let { b.name = it }
            jsUndefined()
        }
        methodId == 2703 -> (obj as? BaseBook)?.let { b ->
            toNullableString(args.getOrNull(0))?.let { b.author = it }
            jsUndefined()
        }
        methodId == 2704 -> (obj as? BaseBook)?.let { b ->
            toNullableString(args.getOrNull(0))?.let { b.origin = it }
            jsUndefined()
        }
        methodId == 2705 -> (obj as? BaseBook)?.let { b ->
            toNullableString(args.getOrNull(0))?.let { b.originName = it }
            jsUndefined()
        }
        methodId == 2706 -> (obj as? BaseBook)?.let { b -> b.kind = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 2707 -> (obj as? BaseBook)?.let { b -> b.wordCount = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 2708 -> (obj as? BaseBook)?.let { b -> b.variable = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 2709 -> (obj as? BaseBook)?.let { b -> b.infoHtml = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 2710 -> (obj as? BaseBook)?.let { b -> b.tocHtml = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 2711 -> (obj as? BaseBook)?.let { b ->
            toIntOrNull(args.getOrNull(0))?.let { b.type = it }
            jsUndefined()
        }
        methodId == 2712 -> (obj as? BaseBook)?.let { b -> b.coverUrl = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 2713 -> (obj as? BaseBook)?.let { b -> b.intro = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 2714 -> (obj as? BaseBook)?.let { b -> b.latestChapterTitle = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 2715 -> (obj as? BaseBook)?.let { b ->
            toNullableString(args.getOrNull(0))?.let { b.tocUrl = it }
            jsUndefined()
        }
        methodId == 2716 -> (obj as? BaseBook)?.let { b ->
            toIntOrNull(args.getOrNull(0))?.let { b.originOrder = it }
            jsUndefined()
        }

        // ============ Book 实体面 (2717-2733) ============
        methodId == 2717 -> (obj as? Book)?.let { b -> b.customTag = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 2718 -> (obj as? Book)?.let { b -> b.customCoverUrl = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 2719 -> (obj as? Book)?.let { b -> b.customIntro = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 2720 -> (obj as? Book)?.let { b -> b.charset = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 2721 -> (obj as? Book)?.let { b ->
            toLongOrNull(args.getOrNull(0))?.let { b.group = it }
            jsUndefined()
        }
        methodId == 2722 -> (obj as? Book)?.let { b ->
            toLongOrNull(args.getOrNull(0))?.let { b.latestChapterTime = it }
            jsUndefined()
        }
        methodId == 2723 -> (obj as? Book)?.let { b ->
            toLongOrNull(args.getOrNull(0))?.let { b.lastCheckTime = it }
            jsUndefined()
        }
        methodId == 2724 -> (obj as? Book)?.let { b ->
            toIntOrNull(args.getOrNull(0))?.let { b.lastCheckCount = it }
            jsUndefined()
        }
        methodId == 2725 -> (obj as? Book)?.let { b ->
            toIntOrNull(args.getOrNull(0))?.let { b.totalChapterNum = it }
            jsUndefined()
        }
        methodId == 2726 -> (obj as? Book)?.let { b -> b.durChapterTitle = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 2727 -> (obj as? Book)?.let { b ->
            toIntOrNull(args.getOrNull(0))?.let { b.durChapterIndex = it }
            jsUndefined()
        }
        methodId == 2728 -> (obj as? Book)?.let { b ->
            toIntOrNull(args.getOrNull(0))?.let { b.durChapterPos = it }
            jsUndefined()
        }
        methodId == 2729 -> (obj as? Book)?.let { b ->
            toLongOrNull(args.getOrNull(0))?.let { b.durChapterTime = it }
            jsUndefined()
        }
        methodId == 2730 -> (obj as? Book)?.let { b ->
            (args.getOrNull(0) as? Boolean)?.let { b.canUpdate = it }
            jsUndefined()
        }
        methodId == 2731 -> (obj as? Book)?.let { b ->
            toIntOrNull(args.getOrNull(0))?.let { b.order = it }
            jsUndefined()
        }
        methodId == 2732 -> (obj as? Book)?.let { b ->
            toLongOrNull(args.getOrNull(0))?.let { b.syncTime = it }
            jsUndefined()
        }
        methodId == 2733 -> (obj as? Book)?.let { b ->
            (args.getOrNull(0) as? List<*>)?.let { list ->
                b.downloadUrls = list.map { it?.toString() ?: "" }
            }
            jsUndefined()
        }
        // 2734 lastChapterIndex: val 只读, 无 setter (两端一致)

        // ============ BookChapter 面 (2801-2816) ============
        methodId == 2801 -> (obj as? BookChapter)?.let { b ->
            toNullableString(args.getOrNull(0))?.let { b.url = it }
            jsUndefined()
        }
        methodId == 2802 -> (obj as? BookChapter)?.let { b ->
            toNullableString(args.getOrNull(0))?.let { b.title = it }
            jsUndefined()
        }
        methodId == 2803 -> (obj as? BookChapter)?.let { b ->
            toNullableString(args.getOrNull(0))?.let { b.bookUrl = it }
            jsUndefined()
        }
        methodId == 2804 -> (obj as? BookChapter)?.let { b ->
            toIntOrNull(args.getOrNull(0))?.let { b.index = it }
            jsUndefined()
        }
        methodId == 2805 -> (obj as? BookChapter)?.let { b ->
            (args.getOrNull(0) as? Boolean)?.let { b.isVolume = it }
            jsUndefined()
        }
        methodId == 2806 -> (obj as? BookChapter)?.let { b ->
            (args.getOrNull(0) as? Boolean)?.let { b.isVip = it }
            jsUndefined()
        }
        methodId == 2807 -> (obj as? BookChapter)?.let { b ->
            (args.getOrNull(0) as? Boolean)?.let { b.isPay = it }
            jsUndefined()
        }
        methodId == 2808 -> (obj as? BookChapter)?.let { b -> b.resourceUrl = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 2809 -> (obj as? BookChapter)?.let { b -> b.tag = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 2810 -> (obj as? BookChapter)?.let { b -> b.wordCount = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 2811 -> (obj as? BookChapter)?.let { b -> b.start = toLongOrNull(args.getOrNull(0)); jsUndefined() }
        methodId == 2812 -> (obj as? BookChapter)?.let { b -> b.end = toLongOrNull(args.getOrNull(0)); jsUndefined() }
        methodId == 2813 -> (obj as? BookChapter)?.let { b -> b.variable = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 2814 -> (obj as? BookChapter)?.let { b -> b.startFragmentId = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 2815 -> (obj as? BookChapter)?.let { b -> b.endFragmentId = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 2816 -> (obj as? BookChapter)?.let { b -> b.titleMD5 = toNullableString(args.getOrNull(0)); jsUndefined() }

        // ============ BookSource 面 (2901-2926) ============
        methodId == 2901 -> (obj as? BookSource)?.let { b ->
            toNullableString(args.getOrNull(0))?.let { b.bookSourceUrl = it }
            jsUndefined()
        }
        methodId == 2902 -> (obj as? BookSource)?.let { b ->
            toNullableString(args.getOrNull(0))?.let { b.bookSourceName = it }
            jsUndefined()
        }
        methodId == 2903 -> (obj as? BookSource)?.let { b -> b.bookSourceGroup = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 2904 -> (obj as? BookSource)?.let { b ->
            toIntOrNull(args.getOrNull(0))?.let { b.bookSourceType = it }
            jsUndefined()
        }
        methodId == 2905 -> (obj as? BookSource)?.let { b -> b.bookUrlPattern = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 2906 -> (obj as? BookSource)?.let { b ->
            toIntOrNull(args.getOrNull(0))?.let { b.customOrder = it }
            jsUndefined()
        }
        methodId == 2907 -> (obj as? BookSource)?.let { b ->
            (args.getOrNull(0) as? Boolean)?.let { b.enabled = it }
            jsUndefined()
        }
        methodId == 2908 -> (obj as? BookSource)?.let { b ->
            (args.getOrNull(0) as? Boolean)?.let { b.enabledExplore = it }
            jsUndefined()
        }
        methodId == 2909 -> (obj as? BookSource)?.let { b ->
            (args.getOrNull(0) as? Boolean)?.let { b.enabledReview = it }
            jsUndefined()
        }
        methodId == 2910 -> (obj as? BookSource)?.let { b -> b.loginCheckJs = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 2911 -> (obj as? BookSource)?.let { b -> b.coverDecodeJs = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 2912 -> (obj as? BookSource)?.let { b -> b.bookSourceComment = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 2913 -> (obj as? BookSource)?.let { b -> b.variableComment = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 2914 -> (obj as? BookSource)?.let { b ->
            toLongOrNull(args.getOrNull(0))?.let { b.lastUpdateTime = it }
            jsUndefined()
        }
        methodId == 2915 -> (obj as? BookSource)?.let { b ->
            toLongOrNull(args.getOrNull(0))?.let { b.respondTime = it }
            jsUndefined()
        }
        methodId == 2916 -> (obj as? BookSource)?.let { b ->
            toIntOrNull(args.getOrNull(0))?.let { b.weight = it }
            jsUndefined()
        }
        methodId == 2917 -> (obj as? BookSource)?.let { b -> b.exploreUrl = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 2918 -> (obj as? BookSource)?.let { b -> b.exploreScreen = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 2919 -> (obj as? BookSource)?.let { b ->
            toIntOrNull(args.getOrNull(0))?.let { b.exploreStyle = it }
            jsUndefined()
        }
        methodId == 2920 -> (obj as? BookSource)?.let { b -> b.ruleExplore = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 2921 -> (obj as? BookSource)?.let { b -> b.searchUrl = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 2922 -> (obj as? BookSource)?.let { b -> b.ruleSearch = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 2923 -> (obj as? BookSource)?.let { b -> b.ruleBookInfo = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 2924 -> (obj as? BookSource)?.let { b -> b.ruleToc = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 2925 -> (obj as? BookSource)?.let { b -> b.ruleContent = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 2926 -> (obj as? BookSource)?.let { b -> b.ruleReview = toNullableString(args.getOrNull(0)); jsUndefined() }

        // ============ BaseSource 面 (3001-3007) ============
        methodId == 3001 -> (obj as? BaseSource)?.let { b -> b.concurrentRate = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 3002 -> (obj as? BaseSource)?.let { b -> b.loginUrl = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 3003 -> (obj as? BaseSource)?.let { b -> b.loginUi = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 3004 -> (obj as? BaseSource)?.let { b -> b.header = toNullableString(args.getOrNull(0)); jsUndefined() }
        methodId == 3005 -> (obj as? BaseSource)?.let { b -> b.enabledCookieJar = args.getOrNull(0) as? Boolean; jsUndefined() }
        methodId == 3006 -> (obj as? BaseSource)?.let { b -> b.enableDangerousApi = args.getOrNull(0) as? Boolean; jsUndefined() }
        methodId == 3007 -> (obj as? BaseSource)?.let { b -> b.jsLib = toNullableString(args.getOrNull(0)); jsUndefined() }

        // ============ AnalyzeUrlCore 面 (3102/3106/3108) ============
        // 3101 rawUrl(val) / 3103 type(val get) / 3104 serverID(val get) / 3105 headerMap(val) 只读,
        // 3107 urlAfterJs(protected set) 两端均不可写 (JVM getMethods 不含 protected setter)
        methodId == 3102 -> (obj as? AnalyzeUrlCore)?.let { b ->
            toNullableString(args.getOrNull(0))?.let { b.urlNoQuery = it }
            jsUndefined()
        }
        methodId == 3106 -> (obj as? AnalyzeUrlCore)?.let { b ->
            toNullableString(args.getOrNull(0))?.let { b.url = it }
            jsUndefined()
        }
        methodId == 3108 -> (obj as? AnalyzeUrlCore)?.let { b -> b.encodedParams = toNullableString(args.getOrNull(0)); jsUndefined() }

        // ============ AnalyzeRuleCore 面 (3110) ============
        // 3109 chapter / 3112 ruleData (嵌套对象, JS 参数面无法还原) / 3111 variables
        // (JS plain object 参数无法还原为 Kotlin Map) 不写, 报告"未覆盖"
        methodId == 3110 -> (obj as? AnalyzeRuleCore)?.let { b -> b.nextChapterUrl = toNullableString(args.getOrNull(0)); jsUndefined() }

        // ============ ksoup Element/Node 方法面 (3200-3299, src binding 逐项循环) ============
        // 对齐 JVM 端 JavaObjectBridge 反射 ksoup 真实 API (方法名直配); Element 独有方法用 as? Element
        methodId == 3201 -> (obj as? Element)?.let { stringToJsValue(ctx, it.text()) } // text()
        methodId == 3202 -> (obj as? Element)?.let { stringToJsValue(ctx, it.ownText()) } // ownText()
        methodId == 3203 -> (obj as? Element)?.let { stringToJsValue(ctx, it.html()) } // html()
        methodId == 3204 -> (obj as? Node)?.let { stringToJsValue(ctx, it.outerHtml()) } // outerHtml()
        methodId == 3205 -> (obj as? Node)?.let { stringToJsValue(ctx, it.toString()) } // toString()
        methodId == 3206 -> (obj as? Node)?.let { stringToJsValue(ctx, it.attr(args.getString(0))) } // attr(name)
        methodId == 3207 -> (obj as? Node)?.let {
            qjs_NewBool(ctx, if (it.hasAttr(args.getString(0))) 1 else 0)
        } // hasAttr(name) → Boolean
        methodId == 3208 -> (obj as? Node)?.let { stringToJsValue(ctx, it.absUrl(args.getString(0))) } // absUrl(name)
        methodId == 3209 -> (obj as? Element)?.let {
            nodeListToJsHandleArray(ctx, it.select(args.getString(0)))
        } // select(css) → handle 数组
        methodId == 3210 -> (obj as? Element)?.let {
            nodeListToJsHandleArray(ctx, it.children())
        } // children() → handle 数组
        methodId == 3211 -> (obj as? Node)?.let { node ->
            val p = node.parent()
            if (p == null) jsNull() else qjs_NewFloat64(ctx, registerNodeHandle(p).toDouble())
        } // parent() → 单 handle 或 null
        methodId == 3212 -> (obj as? Element)?.let { stringToJsValue(ctx, it.tagName()) } // tagName()
        methodId == 3213 -> (obj as? Element)?.let { stringToJsValue(ctx, it.id()) } // id()
        methodId == 3214 -> (obj as? Element)?.let { stringToJsValue(ctx, it.className()) } // className()
        methodId == 3215 -> (obj as? Element)?.let { stringToJsValue(ctx, it.value()) } // value() (ksoup 无 val(), 对齐 JVM 反射面)

        else -> null // 未命中 → undefined (等价 JsDispatchRegistry.NO_MATCH)
    }

    /**
     * 复杂对象属性桥 JS 工厂代码, 由 NativeJsExtensionsBridge.JS_FACTORY_CODE 拼接注入。
     *
     * - __createBaseSourceObj 从 bridge 移入本文件 (完整版: 方法面 + 属性 getter);
     * - 每个 getter 静态绑定 propId (与下方 dispatch 表一一对应, 双处同步收敛在本文件)。
     */
    val JS_PROPERTY_FACTORY_CODE: String = """
// ============ 复杂对象属性桥工厂 (NativeJsPropertyBridge, propId 1700-2199) ============

function __createBaseSourceObj(handle) {
    if (!handle || handle <= 0) return null;
    // BaseSource 实现 JsExtensionsCommon, 继承完整 java 方法面 (ajax/getHeaderMap/put/get 等)
    var obj = __createJavaObj(handle);
    obj.__h = handle;
    // getKey/getTag/getSourceType/getLoginJs 已由 KSP 生成表接管 (注入下方标记处);
    // getHeaderMap 特例保持手写 (推断返回 HashMap 无法归类生成);
    // getLoginUrl/getHeader/getConcurrentRate/getJsLib 为属性 getter 方法化 (阶段 3 E5 接管)
    obj.getHeaderMap = function() {
        var s = __nativeDispatch(handle, 1604, []);
        return (s === null || s === undefined) ? null : JSON.parse(s);
    };
    obj.getLoginUrl = function() { return __nativeDispatch(handle, 1605, []); };
    obj.getHeader = function() { return __nativeDispatch(handle, 1606, []); };
    obj.getConcurrentRate = function() { return __nativeDispatch(handle, 1608, []); };
    obj.getJsLib = function() { return __nativeDispatch(handle, 1609, []); };
    // @@methods:__createBaseSourceObj@@
    // BaseSource 属性 (2000-2099, setter = getter + 1000)
    Object.defineProperty(obj, "concurrentRate", { get: function() { return __nativeDispatch(handle, 2001, []); }, set: function(v) { __nativeDispatch(handle, 3001, [v]); } });
    Object.defineProperty(obj, "loginUrl", { get: function() { return __nativeDispatch(handle, 2002, []); }, set: function(v) { __nativeDispatch(handle, 3002, [v]); } });
    Object.defineProperty(obj, "loginUi", { get: function() { return __nativeDispatch(handle, 2003, []); }, set: function(v) { __nativeDispatch(handle, 3003, [v]); } });
    Object.defineProperty(obj, "header", { get: function() { return __nativeDispatch(handle, 2004, []); }, set: function(v) { __nativeDispatch(handle, 3004, [v]); } });
    Object.defineProperty(obj, "enabledCookieJar", { get: function() { return __nativeDispatch(handle, 2005, []); }, set: function(v) { __nativeDispatch(handle, 3005, [v]); } });
    Object.defineProperty(obj, "enableDangerousApi", { get: function() { return __nativeDispatch(handle, 2006, []); }, set: function(v) { __nativeDispatch(handle, 3006, [v]); } });
    Object.defineProperty(obj, "jsLib", { get: function() { return __nativeDispatch(handle, 2007, []); }, set: function(v) { __nativeDispatch(handle, 3007, [v]); } });
    // BookSource 属性 (1900-1999; 非 BookSource 的 BaseSource 取到 undefined)
    Object.defineProperty(obj, "bookSourceUrl", { get: function() { return __nativeDispatch(handle, 1901, []); }, set: function(v) { __nativeDispatch(handle, 2901, [v]); } });
    Object.defineProperty(obj, "bookSourceName", { get: function() { return __nativeDispatch(handle, 1902, []); }, set: function(v) { __nativeDispatch(handle, 2902, [v]); } });
    Object.defineProperty(obj, "bookSourceGroup", { get: function() { return __nativeDispatch(handle, 1903, []); }, set: function(v) { __nativeDispatch(handle, 2903, [v]); } });
    Object.defineProperty(obj, "bookSourceType", { get: function() { return __nativeDispatch(handle, 1904, []); }, set: function(v) { __nativeDispatch(handle, 2904, [v]); } });
    Object.defineProperty(obj, "bookUrlPattern", { get: function() { return __nativeDispatch(handle, 1905, []); }, set: function(v) { __nativeDispatch(handle, 2905, [v]); } });
    Object.defineProperty(obj, "customOrder", { get: function() { return __nativeDispatch(handle, 1906, []); }, set: function(v) { __nativeDispatch(handle, 2906, [v]); } });
    Object.defineProperty(obj, "enabled", { get: function() { return __nativeDispatch(handle, 1907, []); }, set: function(v) { __nativeDispatch(handle, 2907, [v]); } });
    Object.defineProperty(obj, "enabledExplore", { get: function() { return __nativeDispatch(handle, 1908, []); }, set: function(v) { __nativeDispatch(handle, 2908, [v]); } });
    Object.defineProperty(obj, "enabledReview", { get: function() { return __nativeDispatch(handle, 1909, []); }, set: function(v) { __nativeDispatch(handle, 2909, [v]); } });
    Object.defineProperty(obj, "loginCheckJs", { get: function() { return __nativeDispatch(handle, 1910, []); }, set: function(v) { __nativeDispatch(handle, 2910, [v]); } });
    Object.defineProperty(obj, "coverDecodeJs", { get: function() { return __nativeDispatch(handle, 1911, []); }, set: function(v) { __nativeDispatch(handle, 2911, [v]); } });
    Object.defineProperty(obj, "bookSourceComment", { get: function() { return __nativeDispatch(handle, 1912, []); }, set: function(v) { __nativeDispatch(handle, 2912, [v]); } });
    Object.defineProperty(obj, "variableComment", { get: function() { return __nativeDispatch(handle, 1913, []); }, set: function(v) { __nativeDispatch(handle, 2913, [v]); } });
    Object.defineProperty(obj, "lastUpdateTime", { get: function() { return __nativeDispatch(handle, 1914, []); }, set: function(v) { __nativeDispatch(handle, 2914, [v]); } });
    Object.defineProperty(obj, "respondTime", { get: function() { return __nativeDispatch(handle, 1915, []); }, set: function(v) { __nativeDispatch(handle, 2915, [v]); } });
    Object.defineProperty(obj, "weight", { get: function() { return __nativeDispatch(handle, 1916, []); }, set: function(v) { __nativeDispatch(handle, 2916, [v]); } });
    Object.defineProperty(obj, "exploreUrl", { get: function() { return __nativeDispatch(handle, 1917, []); }, set: function(v) { __nativeDispatch(handle, 2917, [v]); } });
    Object.defineProperty(obj, "exploreScreen", { get: function() { return __nativeDispatch(handle, 1918, []); }, set: function(v) { __nativeDispatch(handle, 2918, [v]); } });
    Object.defineProperty(obj, "exploreStyle", { get: function() { return __nativeDispatch(handle, 1919, []); }, set: function(v) { __nativeDispatch(handle, 2919, [v]); } });
    Object.defineProperty(obj, "ruleExplore", { get: function() { return __nativeDispatch(handle, 1920, []); }, set: function(v) { __nativeDispatch(handle, 2920, [v]); } });
    Object.defineProperty(obj, "searchUrl", { get: function() { return __nativeDispatch(handle, 1921, []); }, set: function(v) { __nativeDispatch(handle, 2921, [v]); } });
    Object.defineProperty(obj, "ruleSearch", { get: function() { return __nativeDispatch(handle, 1922, []); }, set: function(v) { __nativeDispatch(handle, 2922, [v]); } });
    Object.defineProperty(obj, "ruleBookInfo", { get: function() { return __nativeDispatch(handle, 1923, []); }, set: function(v) { __nativeDispatch(handle, 2923, [v]); } });
    Object.defineProperty(obj, "ruleToc", { get: function() { return __nativeDispatch(handle, 1924, []); }, set: function(v) { __nativeDispatch(handle, 2924, [v]); } });
    Object.defineProperty(obj, "ruleContent", { get: function() { return __nativeDispatch(handle, 1925, []); }, set: function(v) { __nativeDispatch(handle, 2925, [v]); } });
    Object.defineProperty(obj, "ruleReview", { get: function() { return __nativeDispatch(handle, 1926, []); }, set: function(v) { __nativeDispatch(handle, 2926, [v]); } });
    return obj;
}

function __createBookObj(handle) {
    if (!handle || handle <= 0) return null;
    var obj = {};
    obj.__h = handle;
    // 变量/方法面 (2300-2399, 对齐 JVM 反射可见的 RuleDataInterface 默认方法 + Book 方法)
    obj.getVariable = function(key) { return __nativeDispatch(handle, 2301, [key]); };
    obj.putVariable = function(key, value) { return __nativeDispatch(handle, 2302, [key, value]); };
    obj.putBigVariable = function(key, value) { __nativeDispatch(handle, 2303, [key, value]); };
    obj.getBigVariable = function(key) { return __nativeDispatch(handle, 2304, [key]); };
    obj.putCustomVariable = function(value) { __nativeDispatch(handle, 2305, [value]); };
    obj.getCustomVariable = function() { return __nativeDispatch(handle, 2306, []); };
    obj.getKindList = function() { return __nativeDispatch(handle, 2307, []); };
    obj.getRealAuthor = function() { return __nativeDispatch(handle, 2308, []); };
    // 属性 getter/setter (getter 1701+, setter = getter + 1000)
    Object.defineProperty(obj, "bookUrl", { get: function() { return __nativeDispatch(handle, 1701, []); }, set: function(v) { __nativeDispatch(handle, 2701, [v]); } });
    Object.defineProperty(obj, "name", { get: function() { return __nativeDispatch(handle, 1702, []); }, set: function(v) { __nativeDispatch(handle, 2702, [v]); } });
    Object.defineProperty(obj, "author", { get: function() { return __nativeDispatch(handle, 1703, []); }, set: function(v) { __nativeDispatch(handle, 2703, [v]); } });
    Object.defineProperty(obj, "origin", { get: function() { return __nativeDispatch(handle, 1704, []); }, set: function(v) { __nativeDispatch(handle, 2704, [v]); } });
    Object.defineProperty(obj, "originName", { get: function() { return __nativeDispatch(handle, 1705, []); }, set: function(v) { __nativeDispatch(handle, 2705, [v]); } });
    Object.defineProperty(obj, "kind", { get: function() { return __nativeDispatch(handle, 1706, []); }, set: function(v) { __nativeDispatch(handle, 2706, [v]); } });
    Object.defineProperty(obj, "wordCount", { get: function() { return __nativeDispatch(handle, 1707, []); }, set: function(v) { __nativeDispatch(handle, 2707, [v]); } });
    Object.defineProperty(obj, "variable", { get: function() { return __nativeDispatch(handle, 1708, []); }, set: function(v) { __nativeDispatch(handle, 2708, [v]); } });
    Object.defineProperty(obj, "infoHtml", { get: function() { return __nativeDispatch(handle, 1709, []); }, set: function(v) { __nativeDispatch(handle, 2709, [v]); } });
    Object.defineProperty(obj, "tocHtml", { get: function() { return __nativeDispatch(handle, 1710, []); }, set: function(v) { __nativeDispatch(handle, 2710, [v]); } });
    Object.defineProperty(obj, "type", { get: function() { return __nativeDispatch(handle, 1711, []); }, set: function(v) { __nativeDispatch(handle, 2711, [v]); } });
    Object.defineProperty(obj, "coverUrl", { get: function() { return __nativeDispatch(handle, 1712, []); }, set: function(v) { __nativeDispatch(handle, 2712, [v]); } });
    Object.defineProperty(obj, "intro", { get: function() { return __nativeDispatch(handle, 1713, []); }, set: function(v) { __nativeDispatch(handle, 2713, [v]); } });
    Object.defineProperty(obj, "latestChapterTitle", { get: function() { return __nativeDispatch(handle, 1714, []); }, set: function(v) { __nativeDispatch(handle, 2714, [v]); } });
    Object.defineProperty(obj, "tocUrl", { get: function() { return __nativeDispatch(handle, 1715, []); }, set: function(v) { __nativeDispatch(handle, 2715, [v]); } });
    Object.defineProperty(obj, "originOrder", { get: function() { return __nativeDispatch(handle, 1716, []); }, set: function(v) { __nativeDispatch(handle, 2716, [v]); } });
    // Book 实体属性 (1717-1734)
    Object.defineProperty(obj, "customTag", { get: function() { return __nativeDispatch(handle, 1717, []); }, set: function(v) { __nativeDispatch(handle, 2717, [v]); } });
    Object.defineProperty(obj, "customCoverUrl", { get: function() { return __nativeDispatch(handle, 1718, []); }, set: function(v) { __nativeDispatch(handle, 2718, [v]); } });
    Object.defineProperty(obj, "customIntro", { get: function() { return __nativeDispatch(handle, 1719, []); }, set: function(v) { __nativeDispatch(handle, 2719, [v]); } });
    Object.defineProperty(obj, "charset", { get: function() { return __nativeDispatch(handle, 1720, []); }, set: function(v) { __nativeDispatch(handle, 2720, [v]); } });
    Object.defineProperty(obj, "group", { get: function() { return __nativeDispatch(handle, 1721, []); }, set: function(v) { __nativeDispatch(handle, 2721, [v]); } });
    Object.defineProperty(obj, "latestChapterTime", { get: function() { return __nativeDispatch(handle, 1722, []); }, set: function(v) { __nativeDispatch(handle, 2722, [v]); } });
    Object.defineProperty(obj, "lastCheckTime", { get: function() { return __nativeDispatch(handle, 1723, []); }, set: function(v) { __nativeDispatch(handle, 2723, [v]); } });
    Object.defineProperty(obj, "lastCheckCount", { get: function() { return __nativeDispatch(handle, 1724, []); }, set: function(v) { __nativeDispatch(handle, 2724, [v]); } });
    Object.defineProperty(obj, "totalChapterNum", { get: function() { return __nativeDispatch(handle, 1725, []); }, set: function(v) { __nativeDispatch(handle, 2725, [v]); } });
    Object.defineProperty(obj, "durChapterTitle", { get: function() { return __nativeDispatch(handle, 1726, []); }, set: function(v) { __nativeDispatch(handle, 2726, [v]); } });
    Object.defineProperty(obj, "durChapterIndex", { get: function() { return __nativeDispatch(handle, 1727, []); }, set: function(v) { __nativeDispatch(handle, 2727, [v]); } });
    Object.defineProperty(obj, "durChapterPos", { get: function() { return __nativeDispatch(handle, 1728, []); }, set: function(v) { __nativeDispatch(handle, 2728, [v]); } });
    Object.defineProperty(obj, "durChapterTime", { get: function() { return __nativeDispatch(handle, 1729, []); }, set: function(v) { __nativeDispatch(handle, 2729, [v]); } });
    Object.defineProperty(obj, "canUpdate", { get: function() { return __nativeDispatch(handle, 1730, []); }, set: function(v) { __nativeDispatch(handle, 2730, [v]); } });
    Object.defineProperty(obj, "order", { get: function() { return __nativeDispatch(handle, 1731, []); }, set: function(v) { __nativeDispatch(handle, 2731, [v]); } });
    Object.defineProperty(obj, "syncTime", { get: function() { return __nativeDispatch(handle, 1732, []); }, set: function(v) { __nativeDispatch(handle, 2732, [v]); } });
    Object.defineProperty(obj, "downloadUrls", { get: function() { return __nativeDispatch(handle, 1733, []); }, set: function(v) { __nativeDispatch(handle, 2733, [v]); } });
    Object.defineProperty(obj, "lastChapterIndex", { get: function() { return __nativeDispatch(handle, 1734, []); } }); // val 只读
    return obj;
}

function __createChapterObj(handle) {
    if (!handle || handle <= 0) return null;
    var obj = {};
    obj.__h = handle;
    // 变量/方法面 (2400-2499, 对齐 JVM 反射可见的 RuleDataInterface 默认方法 + BookChapter 方法)
    obj.getVariable = function(key) { return __nativeDispatch(handle, 2401, [key]); };
    obj.putVariable = function(key, value) { return __nativeDispatch(handle, 2402, [key, value]); };
    obj.putBigVariable = function(key, value) { __nativeDispatch(handle, 2403, [key, value]); };
    obj.getBigVariable = function(key) { return __nativeDispatch(handle, 2404, [key]); };
    obj.primaryStr = function() { return __nativeDispatch(handle, 2405, []); };
    obj.getFileName = function() { return __nativeDispatch(handle, 2406, []); };
    obj.getFontName = function() { return __nativeDispatch(handle, 2407, []); };
    // 属性 getter/setter (getter 1801+, setter = getter + 1000)
    Object.defineProperty(obj, "url", { get: function() { return __nativeDispatch(handle, 1801, []); }, set: function(v) { __nativeDispatch(handle, 2801, [v]); } });
    Object.defineProperty(obj, "title", { get: function() { return __nativeDispatch(handle, 1802, []); }, set: function(v) { __nativeDispatch(handle, 2802, [v]); } });
    Object.defineProperty(obj, "bookUrl", { get: function() { return __nativeDispatch(handle, 1803, []); }, set: function(v) { __nativeDispatch(handle, 2803, [v]); } });
    Object.defineProperty(obj, "index", { get: function() { return __nativeDispatch(handle, 1804, []); }, set: function(v) { __nativeDispatch(handle, 2804, [v]); } });
    Object.defineProperty(obj, "isVolume", { get: function() { return __nativeDispatch(handle, 1805, []); }, set: function(v) { __nativeDispatch(handle, 2805, [v]); } });
    Object.defineProperty(obj, "isVip", { get: function() { return __nativeDispatch(handle, 1806, []); }, set: function(v) { __nativeDispatch(handle, 2806, [v]); } });
    Object.defineProperty(obj, "isPay", { get: function() { return __nativeDispatch(handle, 1807, []); }, set: function(v) { __nativeDispatch(handle, 2807, [v]); } });
    Object.defineProperty(obj, "resourceUrl", { get: function() { return __nativeDispatch(handle, 1808, []); }, set: function(v) { __nativeDispatch(handle, 2808, [v]); } });
    Object.defineProperty(obj, "tag", { get: function() { return __nativeDispatch(handle, 1809, []); }, set: function(v) { __nativeDispatch(handle, 2809, [v]); } });
    Object.defineProperty(obj, "wordCount", { get: function() { return __nativeDispatch(handle, 1810, []); }, set: function(v) { __nativeDispatch(handle, 2810, [v]); } });
    Object.defineProperty(obj, "start", { get: function() { return __nativeDispatch(handle, 1811, []); }, set: function(v) { __nativeDispatch(handle, 2811, [v]); } });
    Object.defineProperty(obj, "end", { get: function() { return __nativeDispatch(handle, 1812, []); }, set: function(v) { __nativeDispatch(handle, 2812, [v]); } });
    Object.defineProperty(obj, "variable", { get: function() { return __nativeDispatch(handle, 1813, []); }, set: function(v) { __nativeDispatch(handle, 2813, [v]); } });
    Object.defineProperty(obj, "startFragmentId", { get: function() { return __nativeDispatch(handle, 1814, []); }, set: function(v) { __nativeDispatch(handle, 2814, [v]); } });
    Object.defineProperty(obj, "endFragmentId", { get: function() { return __nativeDispatch(handle, 1815, []); }, set: function(v) { __nativeDispatch(handle, 2815, [v]); } });
    Object.defineProperty(obj, "titleMD5", { get: function() { return __nativeDispatch(handle, 1816, []); }, set: function(v) { __nativeDispatch(handle, 2816, [v]); } });
    return obj;
}

function __createAnalyzeObj(handle) {
    if (!handle || handle <= 0) return null;
    // AnalyzeUrlCore/AnalyzeRuleCore 实现 JsExtensionsCommon, 继承完整方法面
    var obj = __createJavaObj(handle);
    obj.__h = handle;
    Object.defineProperty(obj, "rawUrl", { get: function() { return __nativeDispatch(handle, 2101, []); } }); // val 只读
    Object.defineProperty(obj, "urlNoQuery", { get: function() { return __nativeDispatch(handle, 2102, []); }, set: function(v) { __nativeDispatch(handle, 3102, [v]); } });
    Object.defineProperty(obj, "type", { get: function() { return __nativeDispatch(handle, 2103, []); } }); // val 只读
    Object.defineProperty(obj, "serverID", { get: function() { return __nativeDispatch(handle, 2104, []); } }); // val 只读
    Object.defineProperty(obj, "headerMap", { get: function() {
        var s = __nativeDispatch(handle, 2105, []);
        return (s === null || s === undefined) ? null : JSON.parse(s);
    } }); // val 只读
    Object.defineProperty(obj, "url", { get: function() { return __nativeDispatch(handle, 2106, []); }, set: function(v) { __nativeDispatch(handle, 3106, [v]); } });
    Object.defineProperty(obj, "urlAfterJs", { get: function() { return __nativeDispatch(handle, 2107, []); } }); // protected set 两端不可写
    Object.defineProperty(obj, "encodedParams", { get: function() { return __nativeDispatch(handle, 2108, []); }, set: function(v) { __nativeDispatch(handle, 3108, [v]); } });
    // AnalyzeRuleCore 面 (2109-2112; AnalyzeUrlCore 实例取到 undefined/null)
    Object.defineProperty(obj, "chapter", { get: function() { return __createChapterObj(__nativeDispatch(handle, 2109, [])); } });
    Object.defineProperty(obj, "nextChapterUrl", { get: function() { return __nativeDispatch(handle, 2110, []); }, set: function(v) { __nativeDispatch(handle, 3110, [v]); } });
    Object.defineProperty(obj, "variables", { get: function() { return __nativeDispatch(handle, 2111, []); } });
    Object.defineProperty(obj, "ruleData", { get: function() { return __createBookObj(__nativeDispatch(handle, 2112, [])); } });
    return obj;
}

function __createCookieObj(handle) {
    if (!handle || handle <= 0) return null;
    var obj = {};
    obj.__h = handle;
    // SourceNetworkProvider 方法面 (2500-2599, 对齐 JVM 反射可见接口方法)
    obj.getCookie = function(tag) { return __nativeDispatch(handle, 2501, [tag]); };
    obj.replaceCookie = function(tag, cookie) { __nativeDispatch(handle, 2502, [tag, cookie]); };
    obj.removeCookie = function(tag) { __nativeDispatch(handle, 2503, [tag]); };
    return obj;
}

function __createCacheObj(handle) {
    if (!handle || handle <= 0) return null;
    var obj = {};
    obj.__h = handle;
    // SourceCacheProvider 方法面 (2600-2699, 对齐 JVM 反射可见接口方法)
    obj.get = function(key) { return __nativeDispatch(handle, 2601, [key]); };
    obj.put = function(key, value, saveTime) {
        return arguments.length >= 3 ? __nativeDispatch(handle, 2607, [key, value, saveTime]) : __nativeDispatch(handle, 2602, [key, value]);
    };
    obj.delete = function(key) { __nativeDispatch(handle, 2603, [key]); };
    obj.getFromMemory = function(key) { return __nativeDispatch(handle, 2604, [key]); };
    obj.putMemory = function(key, value) { __nativeDispatch(handle, 2605, [key, value]); };
    obj.deleteMemory = function(key) { __nativeDispatch(handle, 2606, [key]); };
    obj.getInt = function(key) { return __nativeDispatch(handle, 2608, [key]); };
    obj.getLong = function(key) { return __nativeDispatch(handle, 2609, [key]); };
    obj.getDouble = function(key) { return __nativeDispatch(handle, 2610, [key]); };
    obj.getFloat = function(key) { return __nativeDispatch(handle, 2611, [key]); };
    obj.clearMemoryByPrefixes = function(prefixes) { __nativeDispatch(handle, 2612, [prefixes]); };
    return obj;
}

function __createElementObj(handle) {
    if (!handle || handle <= 0) return null;
    var obj = {};
    obj.__h = handle;
    // ksoup Element/Node 方法面 (3200-3299, 对齐 JVM 反射 ksoup API)
    obj.text = function() { return __nativeDispatch(handle, 3201, []); };
    obj.ownText = function() { return __nativeDispatch(handle, 3202, []); };
    obj.html = function() { return __nativeDispatch(handle, 3203, []); };
    obj.outerHtml = function() { return __nativeDispatch(handle, 3204, []); };
    obj.toString = function() { return __nativeDispatch(handle, 3205, []); };
    obj.attr = function(name) { return __nativeDispatch(handle, 3206, [name]); };
    obj.hasAttr = function(name) { return __nativeDispatch(handle, 3207, [name]); };
    obj.absUrl = function(name) { return __nativeDispatch(handle, 3208, [name]); };
    obj.select = function(css) {
        var hs = __nativeDispatch(handle, 3209, [css]);
        if (!hs) return [];
        var out = [];
        for (var i = 0; i < hs.length; i++) out.push(__createElementObj(hs[i]));
        return out;
    };
    obj.children = function() {
        var hs = __nativeDispatch(handle, 3210, []);
        if (!hs) return [];
        var out = [];
        for (var i = 0; i < hs.length; i++) out.push(__createElementObj(hs[i]));
        return out;
    };
    obj.parent = function() { return __createElementObj(__nativeDispatch(handle, 3211, [])); };
    obj.tagName = function() { return __nativeDispatch(handle, 3212, []); };
    obj.id = function() { return __nativeDispatch(handle, 3213, []); };
    obj.className = function() { return __nativeDispatch(handle, 3214, []); };
    obj.value = function() { return __nativeDispatch(handle, 3215, []); };
    return obj;
}
    """.trimIndent()

    // ============ 工具方法: JSValue 构造 ============

    private fun stringToJsValue(ctx: CPointer<JSContext>, s: String?): CValue<JSValue> =
        if (s == null) jsNull() else qjs_NewString(ctx, s)

    private fun boolOrNullToJsValue(ctx: CPointer<JSContext>, b: Boolean?): CValue<JSValue> =
        b?.let { qjs_NewBool(ctx, if (it) 1 else 0) } ?: jsNull()

    private fun longToJsValue(ctx: CPointer<JSContext>, v: Long?): CValue<JSValue> =
        v?.let { qjs_NewFloat64(ctx, it.toDouble()) } ?: jsNull()

    private fun jsNull(): CValue<JSValue> = cValue {
        tag = JS_TAG_NULL.toLong()
    }

    /** 构造 JS undefined JSValue (属性写/无返回值方法的返回值)。 */
    private fun jsUndefined(): CValue<JSValue> = cValue {
        tag = JS_TAG_UNDEFINED.toLong()
    }

    // ============ 工具方法: setter 值转换 (宽松对齐 JVM 端 coerceValue) ============

    /** null 保持 null; String 原样; 其余 toString (JS Number/Boolean → 字符串, 对齐 rhino coerce)。 */
    private fun toNullableString(v: Any?): String? = when (v) {
        null -> null
        is String -> v
        else -> v.toString()
    }

    /** Number 直转; String 解析; 其余 null (调用方对 null 跳过不写)。 */
    private fun toIntOrNull(v: Any?): Int? = when (v) {
        null -> null
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }

    /** Number 直转; String 解析; 其余 null。 */
    private fun toLongOrNull(v: Any?): Long? = when (v) {
        null -> null
        is Number -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }

    // ============ 工具方法: JSValue 构造 (数组/对象) ============

    /** List<String> → JS Array (downloadUrls 等)。 */
    private fun stringListToJsArray(ctx: CPointer<JSContext>, list: List<String>): CValue<JSValue> {
        val arr = JS_NewArray(ctx)
        list.forEachIndexed { i, s ->
            JS_SetPropertyUint32(ctx, arr, i.toUInt(), qjs_NewString(ctx, s))
        }
        return arr
    }

    /** List<Any?> 取 String (越界或类型不符返回 ""), 与 NativeJsExtensionsBridge.getString 同语义。 */
    private fun List<Any?>.getString(index: Int): String =
        (getOrNull(index) as? String) ?: ""

    /** 任意 Kotlin 值 → JSValue (variables 属性/getFromMemory 返回值用, 与 bridge 的 anyToJs 同语义)。 */
    private fun anyToJs(ctx: CPointer<JSContext>, value: Any?): CValue<JSValue> = when (value) {
        null -> jsNull()
        is String -> stringToJsValue(ctx, value)
        is Boolean -> qjs_NewBool(ctx, if (value) 1 else 0)
        is Number -> qjs_NewFloat64(ctx, value.toDouble())
        is Map<*, *> -> {
            val obj = JS_NewObject(ctx)
            for ((k, v) in value) {
                val key = k?.toString() ?: continue
                JS_SetPropertyStr(ctx, obj, key, anyToJs(ctx, v))
            }
            obj
        }

        is List<*> -> {
            val arr = JS_NewArray(ctx)
            value.forEachIndexed { i, v ->
                JS_SetPropertyUint32(ctx, arr, i.toUInt(), anyToJs(ctx, v))
            }
            arr
        }

        else -> stringToJsValue(ctx, value.toString())
    }

    // ============ 工具方法: ksoup 节点 handle (3200-3299 段专用) ============

    /**
     * 注册 ksoup 节点 handle 并登记到当前 scope (select/children/parent 每次返回新节点,
     * 不登记会在逐项循环里泄漏 M×N 个 handle; 由 releaseNewHandles / scope close 统一回收)。
     */
    private fun registerNodeHandle(node: Node): Long {
        val handle = NativeJsExtensionsBridge.registerObject(node)
        NativeJsEngine.currentScope()?.handles?.add(handle)
        return handle
    }

    /** 节点列表 → JS 数组 (handle 数字), JS 侧 __createElementObj 逐个包装。 */
    private fun nodeListToJsHandleArray(ctx: CPointer<JSContext>, nodes: List<Node>): CValue<JSValue> {
        val arr = JS_NewArray(ctx)
        nodes.forEachIndexed { i, node ->
            JS_SetPropertyUint32(ctx, arr, i.toUInt(), qjs_NewFloat64(ctx, registerNodeHandle(node).toDouble()))
        }
        return arr
    }
}
