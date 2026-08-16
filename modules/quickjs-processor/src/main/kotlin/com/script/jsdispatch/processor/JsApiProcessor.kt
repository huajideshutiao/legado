package com.script.jsdispatch.processor

import com.google.devtools.ksp.isConstructor
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Nullability
import com.google.devtools.ksp.symbol.Variance
import com.script.jsdispatch.processor.JsApiProcessor.Companion.NATIVE_EXCLUDED_METHODS

/**
 * @JsApi 静态分派表生成器。
 *
 * 为每个标注类生成 JsApiDispatcher 实现 (when(方法名) + 参数个数/类型分派),
 * 语义逐条对齐 modules/quickjs JavaObjectBridge 的反射路径 (findMethod 重载选择 +
 * coerceArgs coercion + Method.invoke 的 InvocationTargetException 包装)。
 * 生成物为平台无关纯 Kotlin (KClass/is 判定, 无 java 反射), 未来可进 KMP commonMain。
 *
 * 重载决策 (跨平台单实现规范, 以安卓反射行为为基准):
 * - 单变体元数: 无条件 coerce+invoke (= matched.first() 对唯一候选的确定性行为,
 *   含 JSON 字符串→Map、Double→int 等 coerceValue 兜底转换)。
 * - 多变体元数: guard 严格镜像 isArgsCompatible; 兼容者按 specificity 评分取最小
 *   (镜像 paramSpecificityScore); 平分取声明序 (反射的 Class.methods 序是 JVM
 *   未定义行为, 声明序是可跨平台固定的规范化选择)。
 *   全部不兼容 → NO_MATCH 落反射 (JVM 由反射复刻 matched.first() 兜底强转)。
 * - 保守失配面 (一律 NO_MATCH 落反射, 行为零变化):
 *   ① 同名存在任何无法静态化的重载 (suspend/vararg/泛型/@JvmName/不支持参数) →
 *     整个方法名不进 call 表 (防静态子集与反射全集选择不一致);
 *   ② 接口参数遇 Long 实参 (JS function 句柄, SAM 包装是 JVM Proxy 专属);
 *   ③ 数组参数实参含句柄 Map/null 元素等需逐元素 unwrap 的脏形态。
 *
 * 额外纳入非标注类: ksp arg `jsapi.extraClasses` = 逗号分隔 FQN (供并行开发期
 * 不便动源文件的类型)。
 */
class JsApiProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return JsApiProcessor(environment.codeGenerator, environment.logger, environment.options)
    }
}

class JsApiProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>,
) : SymbolProcessor {

    private companion object {
        const val JS_API_FQN = "com.script.jsdispatch.JsApi"
        const val GEN_PKG = "com.script.jsdispatch.generated"
        const val REGISTRY = "com.script.jsdispatch.JsDispatchRegistry"
        const val COERCE = "com.script.jsdispatch.JsCoerce"

        // native 模式 (jsapi.native=true): 无法模板化、保持手写的特殊方法名 (按名字全局匹配,
        // 不分类 — 新接入目标类与名单同名的规整方法也会被排除, 与手写桥现状一致)。
        // (重载/多参分派/多态/数组构造/批量区间/工厂/handle 返回/header 解析/返回参数原样回传/
        //  推断返回类型无法归类 (getHeaderMap: HashMap)/List 返回 (loginUi)/UI 副作用 (login 族))。
        // 名单与 NativeJsExtensionsBridge 手写分支同步维护。
        val NATIVE_EXCLUDED_METHODS: Set<String> = """
            ajax,ajaxAll,base64Decode,base64DecodeToByteArray,base64Encode,bytesToStr,cacheFile,
            connect,createAsymmetricCrypto,createSign,createSymmetricCrypto,digestBase64Str,digestHex,
            downloadFile,encodeURI,evalJS,get,get7zStringContent,getCookie,getHeaderMap,getRarStringContent,
            getSource,getString,getStringList,getZipStringContent,head,HMacBase64,HMacHex,log,login,loginUi,
            logType,md5Encode,md5Encode16,openUrl,post,put,queryBase64TTF,queryTTF,readTxtFile,replaceFont,
            showLoginDialog,showSourceVariableDialog,startBrowser,startBrowserAwait,strToBytes,toURL,
            webView,webViewGetOverrideUrl,webViewGetSource
        """.trimIndent().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        /** REF 返回且不在 NATIVE_HANDLE_METHODS 白名单: 静默跳过 (无法模板化是已知事实, 不算漏)。 */
        const val SKIP_SILENT_REF = "<silent-ref>"

        /** native 生成表 methodId 基址: 5000 段 (远离手写全段 1-1699 与属性桥 1700-3299, 空间充足)。 */
        const val NATIVE_METHOD_ID_BASE = 5000

        // 目标类/声明类 FQN → JS 工厂函数名: 生成方法的 JS 闭包按此分区注入对应工厂函数体内
        // (工厂函数体内有唯一标记注释 `// @@methods:<factory>@@`, 桥拼接时逐工厂替换)。
        // 未列出的类的方法不生成 (无法确定 JS 暴露面)。
        val NATIVE_JS_FACTORY_BY_CLASS: Map<String, String> = mapOf(
            "io.legado.app.help.JsExtensionsCommon" to "__createJavaObj",
            "io.legado.app.data.entities.BaseSource" to "__createBaseSourceObj",
            "io.legado.app.help.http.StrResponse" to "__createStrResponseObj",
            "org.jsoup.Connection.Response" to "__createRespObj",
            // Base<T> 自限定接口的方法 (hasHeader/hasCookie/multiHeaders 等) 只经 Response 对象暴露
            "org.jsoup.Connection.Base" to "__createRespObj",
            "io.legado.app.model.analyzeRule.QueryTTF" to "__createQueryTTFObj",
            "io.legado.app.utils.JsURL" to "__createJsUrlObj",
        )

        // REF 返回 → 对象 handle 的方法白名单 (ownerFqn.name → JS 包装工厂名):
        // 生成 Handle 分支 + JS 闭包工厂包装; 其余 REF 返回一律跳过 (静默, 无法模板化)。
        val NATIVE_HANDLE_METHODS: Map<String, String> = mapOf(
            "org.jsoup.Connection.Response.parse" to "__createElementObj",
        )
    }

    private val nativeMode = options["jsapi.native"] == "true"

    private var done = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (done) return emptyList()
        val targets = LinkedHashMap<String, KSClassDeclaration>()
        resolver.getSymbolsWithAnnotation(JS_API_FQN)
            .filterIsInstance<KSClassDeclaration>()
            .forEach { targets[it.qualifiedName!!.asString()] = it }
        // native 模式不处理 extraClasses (app 端 JVM 专用: BaseSource/CacheManager 在 native 桥无分派)
        if (!nativeMode) {
            options["jsapi.extraClasses"]?.split(',')?.forEach { raw ->
                val fqn = raw.trim()
                if (fqn.isNotEmpty()) {
                    val decl = resolver.getClassDeclarationByName(resolver.getKSNameFromString(fqn))
                    if (decl != null) targets[fqn] = decl
                    else logger.warn("jsapi.extraClasses: class not found: $fqn")
                }
            }
        } else {
            // native 模式目标类 = JsExtensionsCommon (java 对象方法面, AnalyzeRuleCore/AnalyzeUrlCore
            // 继承它, cast 到接口即覆盖全部实现类) + jsapi.nativeTargets 指定的对象类型类
            // (Connection.Response/StrResponse/BaseSource 等, 桥侧各有专属 JS 工厂函数,
            // 生成闭包按 NATIVE_JS_FACTORY_BY_CLASS 分区注入, 不再有同名冲突)。
            val nativeTargetFqns = buildSet {
                add("io.legado.app.help.JsExtensionsCommon")
                options["jsapi.nativeTargets"]?.split(',')?.forEach { raw ->
                    val fqn = raw.trim()
                    if (fqn.isNotEmpty()) add(fqn)
                }
            }
            targets.keys.retainAll(nativeTargetFqns)
            for (fqn in nativeTargetFqns) {
                if (fqn !in targets) {
                    val decl =
                        resolver.getClassDeclarationByName(resolver.getKSNameFromString(fqn))
                    if (decl != null) targets[fqn] = decl
                    else logger.warn("[jsapi.native] jsapi.nativeTargets 类未找到: $fqn")
                }
            }
        }
        if (targets.isEmpty()) {
            done = true
            return emptyList()
        }
        if (nativeMode) {
            generateNativeDispatch(resolver, targets)
            done = true
            return emptyList()
        }

        val usedNames = HashSet<String>()
        val dispatcherNames = ArrayList<String>()
        val allFiles = ArrayList<KSFile>()
        for ((fqn, decl) in targets.entries.sortedBy { it.key }) {
            var objName = decl.simpleName.asString() + "JsDispatcher"
            var i = 2
            while (!usedNames.add(objName)) objName = decl.simpleName.asString() + "JsDispatcher${i++}"
            generateDispatcher(decl, fqn, objName)
            dispatcherNames.add(objName)
            decl.containingFile?.let { allFiles.add(it) }
        }
        generateTables(dispatcherNames, allFiles)
        done = true
        return emptyList()
    }

    // ============ 模型 ============

    /** 参数/属性类型分类, 决定 coercion / guard / specificity 评分的生成模板。 */
    private sealed class Cat {
        object INT : Cat(); object LONG : Cat(); object SHORT : Cat(); object BYTEP : Cat()
        object FLOAT : Cat(); object DOUBLE : Cat(); object BOOLEAN : Cat(); object CHAR : Cat()
        object STRING : Cat(); object BYTE_ARRAY : Cat(); object STRING_ARRAY : Cat()
        object ANY : Cat()
        class MAPC(val render: String) : Cat()

        /** [guardType]: List<*> / Collection<*> (镜像 isAssignableFrom 判定面)。 */
        class LISTC(val render: String, val guardType: String) : Cat()
        class REF(val fq: String, val isInterface: Boolean) : Cat()
        object UNSUPPORTED : Cat()
    }

    private class ParamModel(val cat: Cat, val nullable: Boolean)

    /** 一个 JVM 可见调用变体: 函数 + 使用前 usedParams 个参数 (JvmOverloads 缺省态)。 */
    private class Variant(
        val fnRender: String,           // 调用名 (Kotlin 源名)
        val params: List<ParamModel>,   // 全部声明参数
        val usedParams: Int,            // 本变体实际传参个数
        val isUnit: Boolean,
        val declOrder: Int,
    )

    private fun categorize(t: KSType): Cat {
        val decl = t.declaration
        val qn = decl.qualifiedName?.asString() ?: return Cat.UNSUPPORTED
        return when (qn) {
            "kotlin.Int" -> Cat.INT
            "kotlin.Long" -> Cat.LONG
            "kotlin.Short" -> Cat.SHORT
            "kotlin.Byte" -> Cat.BYTEP
            "kotlin.Float" -> Cat.FLOAT
            "kotlin.Double" -> Cat.DOUBLE
            "kotlin.Boolean" -> Cat.BOOLEAN
            "kotlin.Char" -> Cat.CHAR
            "kotlin.String" -> Cat.STRING
            "kotlin.ByteArray" -> Cat.BYTE_ARRAY
            "kotlin.Any" -> Cat.ANY
            "kotlin.Array" -> {
                val comp = t.arguments.singleOrNull()?.type?.resolve()
                if (comp?.declaration?.qualifiedName?.asString() == "kotlin.String") Cat.STRING_ARRAY
                else Cat.UNSUPPORTED
            }

            "kotlin.collections.Map", "kotlin.collections.MutableMap" ->
                renderType(t)?.let { Cat.MAPC(it) } ?: Cat.UNSUPPORTED

            "kotlin.collections.List", "kotlin.collections.MutableList" ->
                renderType(t)?.let { Cat.LISTC(it, "kotlin.collections.List<*>") }
                    ?: Cat.UNSUPPORTED

            "kotlin.collections.Collection", "kotlin.collections.MutableCollection" ->
                renderType(t)?.let { Cat.LISTC(it, "kotlin.collections.Collection<*>") }
                    ?: Cat.UNSUPPORTED

            else -> {
                val cls = decl as? KSClassDeclaration ?: return Cat.UNSUPPORTED
                if (t.arguments.isNotEmpty()) return Cat.UNSUPPORTED
                Cat.REF(qn, cls.classKind == ClassKind.INTERFACE)
            }
        }
    }

    /** 渲染类型为可写进 cast 的 Kotlin 源码 (FQN + 泛型实参)。失败返回 null。 */
    private fun renderType(t: KSType): String? {
        val qn = t.declaration.qualifiedName?.asString() ?: return null
        if (t.arguments.isEmpty()) return qn
        val args = t.arguments.map { arg ->
            if (arg.variance == Variance.STAR) "*"
            else {
                val at = arg.type?.resolve() ?: return null
                val inner = renderType(at) ?: return null
                inner + if (at.nullability == Nullability.NULLABLE) "?" else ""
            }
        }
        return "$qn<${args.joinToString(", ")}>"
    }

    // ============ 生成 ============

    private fun generateDispatcher(decl: KSClassDeclaration, fqn: String, objName: String) {
        val functions = decl.getAllFunctions()
            .filter { it.isPublic() && !it.isConstructor() && it.extensionReceiver == null }
            .filter {
                (it.parentDeclaration as? KSClassDeclaration)
                    ?.qualifiedName?.asString() != "kotlin.Any"
            }
            .toList()

        val properties = decl.getAllProperties()
            .filter { it.isPublic() && it.extensionReceiver == null }
            .filter { p -> p.annotations.none { it.shortName.asString() == "JvmField" } }
            .distinctBy { it.simpleName.asString() }   // override 链去重, 防 when 重复分支
            .toList()

        // hasMethod 名单: 全部公开 JVM 方法名 (含 suspend/vararg 等不进 call 表的) + 属性访问器名
        val methodNames = LinkedHashSet<String>()
        functions.forEach { f ->
            if (f.annotations.none { it.shortName.asString() == "JvmName" }) {
                methodNames.add(f.simpleName.asString())
            }
        }
        properties.forEach { p ->
            methodNames.add(getterName(p))
            if (isSetterVisible(p)) methodNames.add(setterName(p))
        }

        // call 表: 可保真静态化的函数; 同名存在任何静态化不了的重载 → 整名保守退出
        val callable = ArrayList<Pair<String, Variant>>() // kotlinName -> variant
        val droppedNames = HashSet<String>()
        val seen = HashSet<String>()
        var order = 0
        for (f in functions) {
            val name = f.simpleName.asString()
            val excluded = Modifier.SUSPEND in f.modifiers ||
                f.typeParameters.isNotEmpty() ||
                f.parameters.any { it.isVararg } ||
                f.annotations.any { it.shortName.asString() == "JvmName" }
            if (excluded) {
                droppedNames.add(name)
                continue
            }
            val params = f.parameters.map { p ->
                val t = p.type.resolve()
                ParamModel(categorize(t), t.nullability == Nullability.NULLABLE)
            }
            if (params.any { it.cat === Cat.UNSUPPORTED }) {
                droppedNames.add(name)
                continue
            }
            val sig = name + "(" + f.parameters.joinToString(",") {
                it.type.resolve().declaration.qualifiedName?.asString() ?: "?"
            } + ")"
            if (!seen.add(sig)) continue
            val isUnit =
                f.returnType?.resolve()?.declaration?.qualifiedName?.asString() == "kotlin.Unit"
            val hasJvmOverloads = f.annotations.any { it.shortName.asString() == "JvmOverloads" }
            val n = params.size
            // JvmOverloads: 从右数连续带缺省的参数逐个省去, 生成低元数变体
            var minArity = n
            if (hasJvmOverloads) {
                var i = n - 1
                while (i >= 0 && f.parameters[i].hasDefault) i--
                minArity = i + 1
            }
            for (arity in minArity..n) {
                callable.add(name to Variant(name, params, arity, isUnit, order++))
            }
        }
        val cleanCallable = callable.filter { it.first !in droppedNames }

        val sb = StringBuilder()
        sb.appendLine("@file:Suppress(")
        sb.appendLine("    \"UNCHECKED_CAST\", \"DEPRECATION\", \"USELESS_CAST\", \"USELESS_IS_CHECK\",")
        sb.appendLine("    \"SENSELESS_COMPARISON\", \"UNUSED_PARAMETER\", \"UNUSED_VARIABLE\",")
        sb.appendLine("    \"KotlinRedundantDiagnosticSuppress\", \"RemoveRedundantQualifierName\", \"UNUSED_EXPRESSION\"")
        sb.appendLine(")")
        sb.appendLine()
        sb.appendLine("package $GEN_PKG")
        sb.appendLine()
        sb.appendLine("import com.script.jsdispatch.JsApiDispatcher")
        sb.appendLine("import $COERCE")
        sb.appendLine("import $REGISTRY")
        sb.appendLine()
        sb.appendLine("/** [$fqn] 的编译期静态分派表 (KSP 生成, 勿手改)。 */")
        sb.appendLine("internal object $objName : JsApiDispatcher {")
        sb.appendLine()
        sb.appendLine("    override val targetClass: kotlin.reflect.KClass<*> = $fqn::class")
        sb.appendLine()
        sb.append("    private val methodNames: HashSet<String> = hashSetOf(")
        sb.append(methodNames.joinToString(", ") { "\"$it\"" })
        sb.appendLine(")")
        sb.appendLine()
        sb.appendLine("    override fun hasMethod(name: String): Boolean = name in methodNames")
        sb.appendLine()

        emitCall(sb, fqn, cleanCallable, droppedNames, properties)
        emitGetProperty(sb, fqn, properties)
        emitSetProperty(sb, fqn, properties)

        sb.appendLine("}")

        writeFile(objName, sb.toString(), decl.containingFile)
    }

    private fun emitCall(
        sb: StringBuilder,
        fqn: String,
        callable: List<Pair<String, Variant>>,
        droppedNames: Set<String>,
        properties: List<KSPropertyDeclaration>,
    ) {
        sb.appendLine("    override fun call(target: Any, name: String, args: Array<Any?>): Any? {")
        sb.appendLine("        target as $fqn")
        val byName = LinkedHashMap<String, MutableList<Variant>>()
        callable.forEach { (n, v) -> byName.getOrPut(n) { ArrayList() }.add(v) }
        if (byName.isEmpty() && properties.isEmpty()) {
            sb.appendLine("        return JsDispatchRegistry.NO_MATCH")
            sb.appendLine("    }")
            sb.appendLine()
            return
        }
        sb.appendLine("        when (name) {")
        for ((name, variants) in byName) {
            sb.appendLine("            \"$name\" -> {")
            val byArity = LinkedHashMap<Int, MutableList<Variant>>()
            variants.sortedBy { it.declOrder }
                .forEach { byArity.getOrPut(it.usedParams) { ArrayList() }.add(it) }
            for ((arity, vs) in byArity) {
                sb.appendLine("                if (args.size == $arity) {")
                // SAM 兜底: 接口参数遇到 JS function 句柄 (Long) 走反射 SAM 包装路径
                val samPositions = (0 until arity).filter { i ->
                    vs.any { (it.params[i].cat as? Cat.REF)?.isInterface == true }
                }
                for (i in samPositions) {
                    sb.appendLine("                    if (args[$i] is Long) return JsDispatchRegistry.NO_MATCH")
                }
                if (vs.size == 1) {
                    emitStrictPrechecks(sb, "                    ", vs[0])
                    emitInvoke(sb, "                    ", vs[0])
                } else {
                    // 兼容者按 specificity 评分取最小 (镜像 findMethodImpl 的
                    // compatible.minByOrNull { paramSpecificityScore }); 平分取声明序。
                    sb.appendLine("                    var best = -1")
                    sb.appendLine("                    var bestScore = Int.MAX_VALUE")
                    vs.forEachIndexed { idx, v ->
                        val guard = guardExpr(v) ?: "true"
                        val score = scoreExpr(v)
                        sb.appendLine("                    if ($guard) {")
                        sb.appendLine("                        val s = $score")
                        sb.appendLine("                        if (s < bestScore) { bestScore = s; best = $idx }")
                        sb.appendLine("                    }")
                    }
                    sb.appendLine("                    when (best) {")
                    vs.forEachIndexed { idx, v ->
                        sb.appendLine("                        $idx -> {")
                        emitStrictPrechecks(sb, "                            ", v)
                        emitInvoke(sb, "                            ", v)
                        sb.appendLine("                        }")
                    }
                    sb.appendLine("                    }")
                }
                sb.appendLine("                }")
            }
            sb.appendLine("            }")
        }
        // 属性访问器 JVM 名直调 (getX()/setX(v)); 与函数名/保守退出名冲突时让位反射
        for (p in properties) {
            val pName = p.simpleName.asString()
            val getter = getterName(p)
            if (getter !in byName && getter !in droppedNames) {
                sb.appendLine("            \"$getter\" -> {")
                sb.appendLine("                if (args.isEmpty()) return JsCoerce.invokeTarget { target.$pName }")
                sb.appendLine("            }")
            }
            if (isSetterVisible(p)) {
                val setter = setterName(p)
                val t = p.type.resolve()
                val cat = categorize(t)
                val nullable = t.nullability == Nullability.NULLABLE
                if (setter !in byName && setter !in droppedNames && cat !== Cat.UNSUPPORTED) {
                    sb.appendLine("            \"$setter\" -> {")
                    sb.appendLine("                if (args.size == 1) {")
                    emitStrictPrecheckFor(sb, "                    ", cat, "args[0]")
                    sb.appendLine("                    val v0 = ${coerceExpr("args[0]", cat, nullable)}")
                    sb.appendLine("                    return JsCoerce.invokeTarget { target.$pName = ${usageExpr("v0", cat, nullable)}; null }")
                    sb.appendLine("                }")
                    sb.appendLine("            }")
                }
            }
        }
        sb.appendLine("            else -> {}")
        sb.appendLine("        }")
        sb.appendLine("        return JsDispatchRegistry.NO_MATCH")
        sb.appendLine("    }")
        sb.appendLine()
    }

    /**
     * 变体兼容 guard, 严格镜像 isArgsCompatible; 全为 Any 等无约束时返回 null (恒兼容)。
     * 注意包装数值类型 (Int? 等) 反射只认精确包装类 (isAssignableFrom), 不认 Number。
     */
    private fun guardExpr(v: Variant): String? {
        val terms = ArrayList<String>()
        for (i in 0 until v.usedParams) {
            val a = "args[$i]"
            val p = v.params[i]
            when (val c = p.cat) {
                Cat.INT ->
                    terms.add(if (p.nullable) "($a == null || $a is Int)" else "$a is Number")

                Cat.LONG ->
                    terms.add(if (p.nullable) "($a == null || $a is Long)" else "$a is Number")

                Cat.SHORT ->
                    terms.add(if (p.nullable) "($a == null || $a is Short)" else "$a is Number")

                Cat.BYTEP ->
                    terms.add(if (p.nullable) "($a == null || $a is Byte)" else "$a is Number")

                Cat.FLOAT ->
                    terms.add(if (p.nullable) "($a == null || $a is Float)" else "$a is Number")

                Cat.DOUBLE ->
                    terms.add(if (p.nullable) "($a == null || $a is Double)" else "$a is Number")

                Cat.BOOLEAN ->
                    terms.add(if (p.nullable) "($a == null || $a is Boolean)" else "$a is Boolean")

                Cat.CHAR ->
                    terms.add(if (p.nullable) "($a == null || $a is Char)" else "$a is Char")

                Cat.STRING -> terms.add("($a == null || $a is String)")
                Cat.BYTE_ARRAY, Cat.STRING_ARRAY -> terms.add("JsCoerce.isArrayLike($a)")
                is Cat.MAPC -> terms.add("($a == null || $a is kotlin.collections.Map<*, *>)")
                is Cat.LISTC -> terms.add("($a == null || $a is ${c.guardType})")
                is Cat.REF -> terms.add("($a == null || $a is ${c.fq})")
                Cat.ANY -> {}
                Cat.UNSUPPORTED -> {}
            }
        }
        return if (terms.isEmpty()) null else terms.joinToString(" && ")
    }

    /**
     * 变体 specificity 总分表达式 (镜像 paramSpecificityScore 求和, 越低越优先)。
     * 仅在 guard 命中后求值, guard 下取值恒定的类别折叠为常量。
     */
    private fun scoreExpr(v: Variant): String {
        var constSum = 0
        val dyn = ArrayList<String>()
        for (i in 0 until v.usedParams) {
            val a = "args[$i]"
            val p = v.params[i]
            when (val c = p.cat) {
                // 包装(可空)数值/Bool/Char: guard 已锁精确包装类或 null → 0
                Cat.INT -> if (p.nullable) constSum += 0 else dyn.add("JsCoerce.scoreIntPrim($a)")
                Cat.LONG -> if (p.nullable) constSum += 0 else dyn.add("JsCoerce.scoreLongPrim($a)")
                Cat.SHORT -> if (p.nullable) constSum += 0 else dyn.add("JsCoerce.scoreShortPrim($a)")
                Cat.BYTEP -> if (p.nullable) constSum += 0 else dyn.add("JsCoerce.scoreBytePrim($a)")
                Cat.FLOAT -> if (p.nullable) constSum += 0 else dyn.add("JsCoerce.scoreFloatPrim($a)")
                Cat.DOUBLE -> if (p.nullable) constSum += 0 else dyn.add("JsCoerce.scoreDoublePrim($a)")
                Cat.BOOLEAN, Cat.CHAR, Cat.STRING -> constSum += 0
                Cat.BYTE_ARRAY -> dyn.add("JsCoerce.scoreByteArray($a)")
                Cat.STRING_ARRAY -> dyn.add("JsCoerce.scoreStringArray($a)")
                // Map/List 实参对接口参数恒为可赋值(1), null 为 0
                is Cat.MAPC -> dyn.add("(if ($a == null) 0 else 1)")
                is Cat.LISTC -> dyn.add("(if ($a == null) 0 else 1)")
                is Cat.REF -> dyn.add("JsCoerce.scoreRef($a, ${c.fq}::class)")
                // Object 参数最低优先; null 实参反射先判 null → 0
                Cat.ANY -> dyn.add("(if ($a == null) 0 else 10)")
                Cat.UNSUPPORTED -> {}
            }
        }
        if (dyn.isEmpty()) return constSum.toString()
        return if (constSum == 0) dyn.joinToString(" + ")
        else "$constSum + " + dyn.joinToString(" + ")
    }

    /** 数组参数脏形态 precheck: 静态不可保真 (需逐元素 unwrap) → NO_MATCH 落反射。 */
    private fun emitStrictPrechecks(sb: StringBuilder, indent: String, v: Variant) {
        for (i in 0 until v.usedParams) {
            emitStrictPrecheckFor(sb, indent, v.params[i].cat, "args[$i]")
        }
    }

    private fun emitStrictPrecheckFor(sb: StringBuilder, indent: String, cat: Cat, a: String) {
        when (cat) {
            Cat.BYTE_ARRAY ->
                sb.appendLine("${indent}if (!JsCoerce.isByteArrayStrict($a)) return JsDispatchRegistry.NO_MATCH")

            Cat.STRING_ARRAY ->
                sb.appendLine("${indent}if (!JsCoerce.isStringArrayStrict($a)) return JsDispatchRegistry.NO_MATCH")

            else -> {}
        }
    }

    /** 参数 coercion 局部变量表达式 (在 invokeTarget 外求值, 异常裸抛对齐 coerceArgs)。 */
    private fun coerceExpr(a: String, cat: Cat, nullable: Boolean): String = when (cat) {
        Cat.INT -> if (nullable) "JsCoerce.toIntOrNull($a)" else "JsCoerce.toInt($a)"
        Cat.LONG -> if (nullable) "JsCoerce.toLongOrNull($a)" else "JsCoerce.toLong($a)"
        Cat.SHORT -> if (nullable) "JsCoerce.toShortOrNull($a)" else "JsCoerce.toShort($a)"
        Cat.BYTEP -> if (nullable) "JsCoerce.toByteOrNull($a)" else "JsCoerce.toByte($a)"
        Cat.FLOAT -> if (nullable) "JsCoerce.toFloatOrNull($a)" else "JsCoerce.toFloat($a)"
        Cat.DOUBLE -> if (nullable) "JsCoerce.toDoubleOrNull($a)" else "JsCoerce.toDouble($a)"
        Cat.BOOLEAN -> if (nullable) "JsCoerce.toBooleanOrNull($a)" else "JsCoerce.toBooleanArg($a)"
        Cat.CHAR -> if (nullable) "JsCoerce.toCharOrNull($a)" else "JsCoerce.toCharArg($a)"
        Cat.STRING -> "$a?.toString()"
        Cat.BYTE_ARRAY -> "JsCoerce.toByteArrayArg($a)"
        Cat.STRING_ARRAY -> "JsCoerce.toStringArrayArg($a) as kotlin.Array<kotlin.String>?"
        is Cat.MAPC -> "JsCoerce.toMapArg($a) as ${cat.render}?"
        // 不兼容形态抛 IAE (对齐 coerceValue 透传 → invoke argument type mismatch), 而非 CCE
        is Cat.LISTC -> "JsCoerce.refArg($a, $a is ${cat.guardType}) as ${cat.render}?"
        is Cat.REF -> "JsCoerce.refArg($a, $a is ${cat.fq}) as ${cat.fq}?"
        Cat.ANY -> a
        Cat.UNSUPPORTED -> a
    }

    /** 调用处使用表达式: 非空参数 !! 断言留在 invokeTarget 内 (对齐 invoke 内 Intrinsics NPE 被 ITE 包装)。 */
    private fun usageExpr(local: String, cat: Cat, nullable: Boolean): String = when (cat) {
        Cat.INT, Cat.LONG, Cat.SHORT, Cat.BYTEP, Cat.FLOAT, Cat.DOUBLE, Cat.BOOLEAN, Cat.CHAR ->
            local // 非空原生值或可空包装, helper 已定型

        else -> if (nullable) local else "$local!!"
    }

    private fun emitInvoke(sb: StringBuilder, indent: String, v: Variant) {
        val usages = ArrayList<String>()
        for (i in 0 until v.usedParams) {
            val p = v.params[i]
            sb.appendLine("${indent}val a$i = ${coerceExpr("args[$i]", p.cat, p.nullable)}")
            usages.add(usageExpr("a$i", p.cat, p.nullable))
        }
        val call = "target.${v.fnRender}(${usages.joinToString(", ")})"
        if (v.isUnit) {
            sb.appendLine("${indent}return JsCoerce.invokeTarget { $call; null }")
        } else {
            sb.appendLine("${indent}return JsCoerce.invokeTarget { $call }")
        }
    }

    private fun emitGetProperty(
        sb: StringBuilder,
        fqn: String,
        properties: List<KSPropertyDeclaration>,
    ) {
        sb.appendLine("    override fun getProperty(target: Any, name: String): Any? {")
        if (properties.isEmpty()) {
            sb.appendLine("        return JsDispatchRegistry.NO_MATCH")
        } else {
            sb.appendLine("        target as $fqn")
            sb.appendLine("        return when (name) {")
            for (p in properties) {
                val n = p.simpleName.asString()
                // 对齐 getJavaPropertyRaw: getter 抛 Exception 吞掉返回 null (-> NULL_FIELD_MARKER)
                sb.appendLine("            \"$n\" -> try { target.$n } catch (_: Exception) { null }")
            }
            sb.appendLine("            else -> JsDispatchRegistry.NO_MATCH")
            sb.appendLine("        }")
        }
        sb.appendLine("    }")
        sb.appendLine()
    }

    private fun emitSetProperty(
        sb: StringBuilder,
        fqn: String,
        properties: List<KSPropertyDeclaration>,
    ) {
        sb.appendLine("    override fun setProperty(target: Any, name: String, value: Any?): Boolean {")
        val mutable = properties.filter { isSetterVisible(it) }
            .filter { categorize(it.type.resolve()) !== Cat.UNSUPPORTED }
        if (mutable.isEmpty()) {
            sb.appendLine("        return false")
        } else {
            sb.appendLine("        target as $fqn")
            sb.appendLine("        when (name) {")
            for (p in mutable) {
                val n = p.simpleName.asString()
                val t = p.type.resolve()
                val cat = categorize(t)
                val nullable = t.nullability == Nullability.NULLABLE
                sb.appendLine("            \"$n\" -> {")
                // 脏数组形态落反射 (逐元素 unwrap 语义)
                when (cat) {
                    Cat.BYTE_ARRAY ->
                        sb.appendLine("                if (!JsCoerce.isByteArrayStrict(value)) return false")

                    Cat.STRING_ARRAY ->
                        sb.appendLine("                if (!JsCoerce.isStringArrayStrict(value)) return false")

                    else -> {}
                }
                sb.appendLine("                val v0 = ${coerceExpr("value", cat, nullable)}")
                sb.appendLine("                JsCoerce.invokeTarget { target.$n = ${usageExpr("v0", cat, nullable)}; null }")
                sb.appendLine("                return true")
                sb.appendLine("            }")
            }
            sb.appendLine("            else -> {}")
            sb.appendLine("        }")
            sb.appendLine("        return false")
        }
        sb.appendLine("    }")
    }

    private fun generateTables(dispatcherNames: List<String>, files: List<KSFile>) {
        val sb = StringBuilder()
        sb.appendLine("package $GEN_PKG")
        sb.appendLine()
        sb.appendLine("import $REGISTRY")
        sb.appendLine()
        sb.appendLine("/**")
        sb.appendLine(" * 静态分派表聚合注册入口 (KSP 生成, 勿手改)。")
        sb.appendLine(" * JsDispatchRegistry 首查时 Class.forName 触发 <clinit> 完成注册。")
        sb.appendLine(" */")
        sb.appendLine("internal object JsDispatchTables {")
        sb.appendLine("    init {")
        for (n in dispatcherNames) {
            sb.appendLine("        JsDispatchRegistry.register($n)")
        }
        sb.appendLine("    }")
        sb.appendLine("}")
        codeGenerator.createNewFile(
            Dependencies(aggregating = true, *files.toTypedArray()),
            GEN_PKG,
            "JsDispatchTables"
        ).use { it.write(sb.toString().toByteArray()) }
    }

    private fun writeFile(name: String, content: String, vararg files: KSFile?) {
        val nonNull = files.filterNotNull()
        val deps =
            if (nonNull.isNotEmpty()) Dependencies(aggregating = false, *nonNull.toTypedArray())
        else Dependencies(aggregating = false)
        codeGenerator.createNewFile(deps, GEN_PKG, name)
            .use { it.write(content.toByteArray()) }
    }

    // ============ native 输出模式 (jsapi.native=true) ============

    /**
     * native 模式生成: 为 @JsApi 类的规整方法生成分派 + JS 方法表。
     *
     * 只处理声明于目标类自身/继承链的规整方法 (参数可静态化、无重载、非 suspend/vararg/泛型),
     * 且方法名不在 [NATIVE_EXCLUDED_METHODS] (手写桥已注册的存量方法, 避免重复分派)。
     * 生成物为纯 Kotlin (无 cinterop 依赖), 返回值用 [NativeDispatchResult] 密封类,
     * JSValue 转换留在 NativeJsExtensionsBridge (与手写分支同层, 避免生成物依赖 cinterop 符号)。
     *
     * 防漏机制: 扫描到的非规整方法 (无法静态生成) 若不在排除名单, 发 KSP warning 提示,
     * 避免后续新增 @JsApi 函数时忘记在 native 桥手写分派/加入排除名单。
     *
     * @param resolver KSP resolver
     * @param targets @JsApi 标注类 (FQN -> decl)
     */
    private fun generateNativeDispatch(
        resolver: Resolver,
        targets: Map<String, KSClassDeclaration>,
    ) {
        // 收集 (类FQN, 方法) 列表: 只保留规整方法
        data class NativeMethod(
            val clsFqn: String,
            val clsName: String,
            val name: String,
            val params: List<ParamModel>,
            val retCat: Cat,
            val retNullable: Boolean,
            val isUnit: Boolean,
            val handleFactory: String?,  // REF 返回白名单 → JS 包装工厂名 (null = 非 handle 返回)
            val jsFactory: String,       // JS 闭包注入的目标工厂函数名 (分区键)
        )

        val methods = ArrayList<NativeMethod>()

        // 先全量收集候选 (含声明类+签名), 再按 (声明类, 方法名) 判定重载
        data class Candidate(
            val ownerFqn: String,     // 声明方法所在的类/接口 FQN (分派 cast 目标)
            val ownerName: String,
            val name: String,
            val sig: String,
            val params: List<ParamModel>,
            val retQn: String,
            val ret: KSType?,
            val skipReason: String?,  // null = 可生成; SKIP_SILENT_REF = 静默跳过; 其余 = warn
            val handleFactory: String?,
            val jsFactory: String?,
        )

        val all = ArrayList<Candidate>()
        val seenOwnerSigs = HashSet<String>()
        for ((fqn, decl) in targets.entries.sortedBy { it.key }) {
            val functions = decl.getAllFunctions()
                .filter { it.isPublic() && !it.isConstructor() && it.extensionReceiver == null }
                .filter {
                    (it.parentDeclaration as? KSClassDeclaration)
                        ?.qualifiedName?.asString() != "kotlin.Any"
                }
            for (f in functions) {
                // 声明类: 继承链方法取声明处 (接口方法在实现类 getAllFunctions 重复出现,
                // 但只按声明类生成一次, cast 到声明类以覆盖全部实现类)
                val owner = f.parentDeclaration as? KSClassDeclaration
                val ownerFqn = owner?.qualifiedName?.asString() ?: fqn
                val ownerName = owner?.simpleName?.asString() ?: decl.simpleName.asString()
                val name = f.simpleName.asString()
                val params = f.parameters.map { p ->
                    val t = p.type.resolve()
                    ParamModel(categorize(t), t.nullability == Nullability.NULLABLE)
                }
                val ret = f.returnType?.resolve()
                val retQn = ret?.declaration?.qualifiedName?.asString() ?: "?"
                val sig = name + "(" + f.parameters.joinToString(",") {
                    it.type.resolve().declaration.qualifiedName?.asString() ?: "?"
                } + ")"
                // 去重键 = 声明类 + 签名: 不同类同名同签名 (如 BaseSource.evalJS vs AnalyzeUrlCore.evalJS)
                // 语义不同, 各自保留; 同一声明类重复出现 (多实现类继承同接口方法) 只留一次
                if (!seenOwnerSigs.add(ownerFqn + "|" + sig)) continue
                val handleFactory = NATIVE_HANDLE_METHODS[ownerFqn + "." + name]
                val jsFactory = NATIVE_JS_FACTORY_BY_CLASS[ownerFqn]
                // 判定跳过原因 (排除名单内的存量方法不算漏, 不 warn)
                val retCat =
                    if (retQn == "kotlin.Unit" || retQn == "?") null else ret?.let { categorize(it) }
                val skipReason = when {
                    name in NATIVE_EXCLUDED_METHODS -> "已在 NATIVE_EXCLUDED_METHODS 名单(手写桥处理)"
                    jsFactory == null ->
                        "声明类不在 NATIVE_JS_FACTORY_BY_CLASS 映射 (无法确定 JS 工厂分区)"
                    Modifier.SUSPEND in f.modifiers -> "suspend 函数无法静态分派"
                    f.typeParameters.isNotEmpty() -> "泛型函数无法静态分派"
                    f.parameters.any { it.isVararg } -> "vararg 参数无法静态分派"
                    f.annotations.any { it.shortName.asString() == "JvmName" } -> "@JvmName 标注无法静态分派"
                    params.any { c ->
                        val cat = c.cat
                        cat === Cat.UNSUPPORTED || cat is Cat.REF || cat is Cat.MAPC || cat is Cat.LISTC || cat === Cat.STRING_ARRAY
                    } -> "参数含自定义对象/Map/List/数组类型 (需手写编解码)"

                    retCat === Cat.UNSUPPORTED || retCat is Cat.LISTC || retCat === Cat.STRING_ARRAY ->
                        "返回自定义对象/List/数组类型 (需手写 handle 包装)"

                    // 返回 Map → GSON JSON (可生成); 返回 REF 且在 NATIVE_HANDLE_METHODS 白名单 →
                    // Handle (可生成); 白名单外 REF 无法模板化是已知事实 → 静默跳过不算漏
                    retCat is Cat.REF && handleFactory == null -> SKIP_SILENT_REF

                    else -> null
                }
                all.add(
                    Candidate(
                        ownerFqn = ownerFqn,
                        ownerName = ownerName,
                        name = name,
                        sig = sig,
                        params = params,
                        retQn = retQn,
                        ret = ret,
                        skipReason = skipReason,
                        handleFactory = handleFactory,
                        jsFactory = jsFactory,
                    )
                )
            }
        }

        // 按 (声明类, 方法名) 分组统计**可生成**签名数 (JS 层无参数个数分派):
        // 恰 1 个 → 生成 (其余签名因 REF/List/函数参数被 skip, JS 侧本就未暴露该形态,
        //   如 Response.header(String) 生成而 header(String,String)→Response 跳过);
        // >1 个 → 整名排除 + warn; 不同类同名不算重载 (BaseSource.evalJS vs AnalyzeUrlCore.evalJS)。
        val genCountByOwnerName = HashMap<String, Int>()
        all.forEach { c ->
            if (c.skipReason == null) genCountByOwnerName.merge(
                c.ownerFqn + "." + c.name,
                1,
                Int::plus
            )
        }
        val multiGenKey = genCountByOwnerName.filterValues { it > 1 }.keys
        val emittedWarns = HashSet<String>()
        for (c in all) {
            val ownerKey = c.ownerFqn + "." + c.name
            if (ownerKey in multiGenKey) {
                if (emittedWarns.add(ownerKey)) {
                    logger.warn(
                        "[jsapi.native] ${c.ownerFqn}.${c.name} 存在多个可生成签名 (${genCountByOwnerName[ownerKey]} 个), " +
                            "无法自动生成分派: 请手写 native 桥分派或加入 NATIVE_EXCLUDED_METHODS"
                    )
                }
                continue
            }
            if (c.skipReason != null) {
                if (c.skipReason != SKIP_SILENT_REF && c.name !in NATIVE_EXCLUDED_METHODS && emittedWarns.add(
                        ownerKey
                    )
                ) {
                    logger.warn(
                        "[jsapi.native] ${c.ownerFqn}.${c.name} 无法自动生成分派: ${c.skipReason}. " +
                            "请手写 native 桥分派或加入 NATIVE_EXCLUDED_METHODS"
                    )
                }
                continue
            }
            val retNullable = c.ret?.nullability == Nullability.NULLABLE
            methods.add(
                NativeMethod(
                    clsFqn = c.ownerFqn,
                    clsName = c.ownerName,
                    name = c.name,
                    params = c.params,
                    retCat = if (c.retQn == "kotlin.Unit" || c.retQn == "?") Cat.ANY else (c.ret?.let {
                        categorize(
                            it
                        )
                    } ?: Cat.ANY),
                    retNullable = retNullable,
                    isUnit = c.retQn == "kotlin.Unit",
                    handleFactory = c.handleFactory,
                    jsFactory = c.jsFactory!!,
                )
            )
        }
        if (methods.isEmpty()) {
            generateNativeEmpty()
            return
        }

        // methodId: 从 [NATIVE_METHOD_ID_BASE] 起稳定分配 (远离手写全段)
        val assigned = methods.sortedBy { it.clsFqn + "." + it.name }
            .mapIndexed { i, m -> m to (NATIVE_METHOD_ID_BASE + i) }
        val sb = StringBuilder()
        sb.appendLine("@file:Suppress(\"UNCHECKED_CAST\", \"DEPRECATION\", \"UNUSED_PARAMETER\", \"UNUSED_VARIABLE\")")
        sb.appendLine()
        sb.appendLine("package $GEN_PKG")
        sb.appendLine()
        sb.appendLine("import io.legado.app.utils.GSON")
        sb.appendLine("import io.legado.app.utils.toJson")
        sb.appendLine()
        sb.appendLine("/**")
        sb.appendLine(" * native 端 (iOS/鸿蒙) @JsApi 新方法分派表 (KSP 生成, 勿手改)。")
        sb.appendLine(" * 与 NativeJsExtensionsBridge 手写分支共存: 桥 dispatch 先查本表, 未命中落手写。")
        sb.appendLine(" *")
        sb.appendLine(" * dispatch 额外接收 registerHandle (桥的 registerObject 引用): REF 返回白名单方法")
        sb.appendLine(" * (NATIVE_HANDLE_METHODS) 经它注册对象 handle, JS 层由 JS_METHOD_TABLES 中对应工厂闭包")
        sb.appendLine(" * 包装; registerHandle == null 时 Handle(0) → JS null (降级安全)。")
        sb.appendLine(" */")
        sb.appendLine("internal object NativeGeneratedDispatch {")
        sb.appendLine()
        sb.appendLine("    fun dispatch(")
        sb.appendLine("        obj: Any,")
        sb.appendLine("        methodId: Int,")
        sb.appendLine("        args: List<Any?>,")
        sb.appendLine("        registerHandle: ((Any) -> Long)? = null,")
        sb.appendLine("    ): NativeDispatchResult = when {")
        for ((m, id) in assigned) {
            val callArgs = m.params.indices.joinToString(", ") { i -> "a$i" }
            val call = "obj.${m.name}($callArgs)"
            // obj is X 检查在 when 条件成立后, 分支体内 obj 被 smart cast (局部参数不可变)
            sb.appendLine("        obj is ${m.clsFqn} && methodId == $id -> {")
            for (i in m.params.indices) {
                val p = m.params[i]
                sb.appendLine("            val a$i = ${nativeParamExpr("args", i, p)}")
            }
            sb.appendLine(
                "            ${
                    nativeRetExpr(
                        call,
                        m.isUnit,
                        m.retCat,
                        m.retNullable,
                        m.handleFactory
                    )
                }"
            )
            sb.appendLine("        }")
        }
        sb.appendLine("        else -> NativeDispatchResult.NONE")
        sb.appendLine("    }")
        sb.appendLine()
        // JS 方法表 (按 JS 工厂分区): 桥拼接时把每张表替换进对应工厂函数体内的
        // `// @@methods:<factory>@@` 标记行 (标记行 4 空格缩进, 表行同 4 空格)。
        // 生成物是 obj.xxx = function 裸语句, 必须注入工厂函数体内 (局部 obj/handle),
        // 拼在全局作用域会 ReferenceError。
        sb.appendLine("    val JS_METHOD_TABLES: Map<String, String> = mapOf(")
        for ((factory, group) in assigned.groupByTo(LinkedHashMap()) { it.first.jsFactory }) {
            sb.appendLine("        \"$factory\" to \"\"\"")
            for ((m, id) in group) {
                val params = m.params.indices.joinToString(", ") { "p$it" }
                val argsArr = m.params.indices.joinToString(", ") { "p$it" }
                val body = when {
                    m.isUnit -> "__nativeDispatch(handle, $id, [$argsArr]);"
                    // REF 白名单: 返回 handle 由 JS 工厂包装 (对齐手写 obj.parse = ...__createElementObj)
                    m.handleFactory != null ->
                        "return ${m.handleFactory}(__nativeDispatch(handle, $id, [$argsArr]));"
                    // Map → JSON 字符串, JS 侧 JSON.parse (对齐手写 706/707/1604 闭包; 可空 Map null 传播)
                    m.retCat is Cat.MAPC -> {
                        val fallback = if (m.retNullable) "null" else "{}"
                        "var s = __nativeDispatch(handle, $id, [$argsArr]); " +
                            "return (s === null || s === undefined) ? $fallback : JSON.parse(s);"
                    }

                    else -> "return __nativeDispatch(handle, $id, [$argsArr]);"
                }
                sb.appendLine("    obj.${m.name} = function($params) { $body }")
            }
            // 结束定界符列 0: """ 前的缩进会进入字符串内容, 表行保持 4 空格字面缩进
            sb.appendLine("\"\"\",")
        }
        sb.appendLine("    )")
        sb.appendLine("}")
        writeFile(
            "NativeGeneratedDispatch",
            sb.toString(),
            *targets.values.mapNotNull { it.containingFile }.toTypedArray(),
        )
    }

    /** native 参数表达式: List<Any?> 取第 [index] 个并转换。 */
    private fun nativeParamExpr(listName: String, index: Int, p: ParamModel): String {
        val a = "$listName.getOrNull($index)"
        return when (val c = p.cat) {
            Cat.STRING -> if (p.nullable) "$a as? String" else "($a as? String) ?: \"\""
            Cat.INT -> if (p.nullable) "($a as? Number)?.toInt()" else "($a as? Number)?.toInt() ?: 0"
            Cat.LONG -> if (p.nullable) "($a as? Number)?.toLong()" else "($a as? Number)?.toLong() ?: 0L"
            Cat.SHORT -> if (p.nullable) "($a as? Number)?.toShort()" else "($a as? Number)?.toShort() ?: 0"
            Cat.BYTEP -> if (p.nullable) "($a as? Number)?.toByte()" else "($a as? Number)?.toByte() ?: 0"
            Cat.FLOAT -> if (p.nullable) "($a as? Number)?.toFloat()" else "($a as? Number)?.toFloat() ?: 0f"
            Cat.DOUBLE -> if (p.nullable) "($a as? Number)?.toDouble()" else "($a as? Number)?.toDouble() ?: 0.0"
            Cat.BOOLEAN -> if (p.nullable) "$a as? Boolean" else "($a as? Boolean) ?: false"
            Cat.CHAR -> if (p.nullable) "$a as? Char" else "($a as? Char) ?: ' '"
            Cat.ANY -> a
            Cat.BYTE_ARRAY -> "($a as? List<*>)" +
                "?.mapNotNull { (it as? Number)?.toInt()?.toByte() }?.toByteArray()" +
                if (p.nullable) "" else " ?: ByteArray(0)"
            else -> a
        }
    }

    /** native 返回值表达式: 包装为 NativeDispatchResult。 */
    private fun nativeRetExpr(
        call: String,
        isUnit: Boolean,
        retCat: Cat,
        retNullable: Boolean,
        handleFactory: String? = null
    ): String {
        if (isUnit) return "$call; NativeDispatchResult.UNIT"
        return when (val c = retCat) {
            Cat.STRING -> "NativeDispatchResult.Str($call)"
            Cat.INT -> "NativeDispatchResult.Int($call)"
            Cat.LONG -> "NativeDispatchResult.Long($call)"
            Cat.SHORT -> "NativeDispatchResult.Int($call)"
            Cat.BYTEP -> "NativeDispatchResult.Int($call)"
            Cat.FLOAT, Cat.DOUBLE -> "NativeDispatchResult.Double($call)"
            Cat.BOOLEAN -> "NativeDispatchResult.Bool($call)"
            Cat.CHAR -> "NativeDispatchResult.Str($call.toString())"
            Cat.BYTE_ARRAY -> "NativeDispatchResult.Bytes($call)"
            // Map → GSON JSON 字符串 (对齐手写 706/707/1604; 可空 Map null → Str(null) → jsNull)
            is Cat.MAPC ->
                if (retNullable) "NativeDispatchResult.Str($call?.let { GSON.toJson(it) })"
                else "NativeDispatchResult.Str(GSON.toJson($call))"
            // 仅 NATIVE_HANDLE_METHODS 白名单到达: 注册对象 handle (0 = null), JS 层工厂闭包包装
            is Cat.REF ->
                "val h = $call; NativeDispatchResult.Handle(" +
                    "if (h == null) 0L else (registerHandle?.invoke(h) ?: 0L))"
            else -> "NativeDispatchResult.AnyVal($call)"
        }
    }

    /** native 模式无可生成方法时输出空表 (保持编译期引用一致)。 */
    private fun generateNativeEmpty() {
        val sb = StringBuilder()
        sb.appendLine("package $GEN_PKG")
        sb.appendLine()
        sb.appendLine("/** native 端 @JsApi 新方法分派表 (KSP 生成, 勿手改) — 当前无新增方法。 */")
        sb.appendLine("internal object NativeGeneratedDispatch {")
        sb.appendLine("    fun dispatch(")
        sb.appendLine("        obj: Any,")
        sb.appendLine("        methodId: Int,")
        sb.appendLine("        args: List<Any?>,")
        sb.appendLine("        registerHandle: ((Any) -> Long)? = null,")
        sb.appendLine("    ): NativeDispatchResult = NativeDispatchResult.NONE")
        sb.appendLine("    val JS_METHOD_TABLES: Map<String, String> = emptyMap()")
        sb.appendLine("}")
        writeFile("NativeGeneratedDispatch", sb.toString())
    }

    // ============ 属性访问器 JVM 命名 ============

    private fun getterName(p: KSPropertyDeclaration): String {
        val n = p.simpleName.asString()
        return if (n.length > 2 && n.startsWith("is") && n[2].isUpperCase()) n
        else "get" + n.replaceFirstChar { it.uppercaseChar() }
    }

    private fun setterName(p: KSPropertyDeclaration): String {
        val n = p.simpleName.asString()
        return if (n.length > 2 && n.startsWith("is") && n[2].isUpperCase()) {
            "set" + n.substring(2)
        } else "set" + n.replaceFirstChar { it.uppercaseChar() }
    }

    private fun isSetterVisible(p: KSPropertyDeclaration): Boolean {
        if (!p.isMutable) return false
        val setter = p.setter ?: return true
        return Modifier.PRIVATE !in setter.modifiers &&
            Modifier.PROTECTED !in setter.modifiers &&
            Modifier.INTERNAL !in setter.modifiers
    }
}
