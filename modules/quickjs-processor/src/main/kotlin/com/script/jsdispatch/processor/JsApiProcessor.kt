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
    }

    private var done = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (done) return emptyList()
        val targets = LinkedHashMap<String, KSClassDeclaration>()
        resolver.getSymbolsWithAnnotation(JS_API_FQN)
            .filterIsInstance<KSClassDeclaration>()
            .forEach { targets[it.qualifiedName!!.asString()] = it }
        options["jsapi.extraClasses"]?.split(',')?.forEach { raw ->
            val fqn = raw.trim()
            if (fqn.isNotEmpty()) {
                val decl = resolver.getClassDeclarationByName(resolver.getKSNameFromString(fqn))
                if (decl != null) targets[fqn] = decl
                else logger.warn("jsapi.extraClasses: class not found: $fqn")
            }
        }
        if (targets.isEmpty()) {
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

    private fun writeFile(name: String, content: String, file: KSFile?) {
        val deps = if (file != null) Dependencies(aggregating = false, file)
        else Dependencies(aggregating = false)
        codeGenerator.createNewFile(deps, GEN_PKG, name)
            .use { it.write(content.toByteArray()) }
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
