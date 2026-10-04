// dex2jar 2.4.38 对 R8 新版"接收者类构造器"模式 (new-instance Xt; invoke-direct Lk;.<init>
// —— 具体类无自有 <init>, ART 按接收者实际类解析构造器) 的转换缺陷修复: ir 的 NewTransformer
// 用 invoke 的 owner (抽象基类) 而非 NEW 的具体类型生成 NewExpr, 产出 new AbstractClass(),
// JVM 上抛 InstantiationError。本修复以 dex 指令序为基准还原各构造器调用点的具体类型:
// 重写 jar 的 NEW/INVOKESPECIAL, 并给缺失 <init> 的具体类合成转发构造器 (JVM 校验要求
// <init> 与接收者同类, 仅指向基类构造器无法通过校验)。
package io.legado.desktop.extension

import com.googlecode.d2j.reader.DexFileReader
import com.googlecode.d2j.reader.Op
import com.googlecode.d2j.visitors.DexClassVisitor
import com.googlecode.d2j.visitors.DexCodeVisitor
import com.googlecode.d2j.visitors.DexFileVisitor
import com.googlecode.d2j.visitors.DexMethodVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.lang.reflect.Modifier
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal object CtorSiteFixer {

    private const val ACC_ABSTRACT = 0x0400

    /** dex 类型名 → JVM 内部名 ("Lt;" → "t"; dex2jar dontSanitizeNames 下 jar 与 dex 同名)。 */
    private fun toInternal(type: String): String =
        type.removePrefix("L").removeSuffix(";").replace('.', '/')

    /**
     * 宿主 abstract 类缓存: compileOnly 类 (Filter$Text 等 shim/source-api 类) 不在扩展 dex 里,
     * dex 扫描收不到它们的 abstract 标志; 这些类的构造调用点同样产出 new AbstractClass(),
     * 必须并入修复判定, 否则扩展调用即 InstantiationError。判定走宿主 classpath 反射, 结果缓存。
     */
    private val hostAbstractCache = HashMap<String, Boolean>()

    private fun isHostAbstract(internalName: String): Boolean = synchronized(hostAbstractCache) {
        hostAbstractCache.getOrPut(internalName) {
            runCatching {
                Modifier.isAbstract(
                    Class.forName(internalName.replace('/', '.'), false, CtorSiteFixer::class.java.classLoader).modifiers,
                )
            }.getOrDefault(false)
        }
    }

    /** dex 构造器调用点: owner=被调的抽象构造器类, concrete=NEW 的接收者具体类, ctorDesc=构造器描述。 */
    private data class CtorSite(val owner: String, val concrete: String, val ctorDesc: String)

    /**
     * dex 扫描: key = "类内部名::方法名::desc" → 方法内按序的构造器调用点。
     * 以 new-instance 落寄存器 + invoke-direct 首参取寄存器 配对。
     */
    private fun collectDexCtorSites(dexBytes: ByteArray): Map<String, List<CtorSite>> {
        val records = HashMap<String, MutableList<CtorSite>>()
        val abstractClasses = HashSet<String>()

        DexFileReader(dexBytes).accept(object : DexFileVisitor() {
            override fun visit(
                accessFlags: Int,
                className: String,
                superClass: String,
                interfaceNames: Array<out String>,
            ): DexClassVisitor? {
                val internal = toInternal(className)
                if (accessFlags and ACC_ABSTRACT != 0) {
                    abstractClasses.add(internal)
                }
                return object : DexClassVisitor() {
                    override fun visitMethod(accessFlags: Int, method: com.googlecode.d2j.Method): DexMethodVisitor? {
                        val name = method.name
                        val desc = method.desc
                        val key = "$internal::$name::$desc"
                        val newTypes = HashMap<Int, String>()
                        return object : DexMethodVisitor() {
                            override fun visitCode(): DexCodeVisitor = object : DexCodeVisitor() {
                                override fun visitTypeStmt(op: Op, a: Int, b: Int, type: String) {
                                    if (op == Op.NEW_INSTANCE) {
                                        newTypes[a] = toInternal(type)
                                    }
                                }

                                override fun visitMethodStmt(
                                    op: Op,
                                    args: IntArray,
                                    method: com.googlecode.d2j.Method,
                                ) {
                                    if (op == Op.INVOKE_DIRECT || op == Op.INVOKE_DIRECT_RANGE) {
                                        val receiver = args.firstOrNull() ?: return
                                        val newType = newTypes[receiver] ?: return
                                        val owner = toInternal(method.owner)
                                        if (newType != owner && (owner in abstractClasses || isHostAbstract(owner))) {
                                            records.getOrPut(key) { mutableListOf() }
                                                .add(CtorSite(owner, newType, method.desc))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        })
        return records
    }

    /** 就地修复 dex2jar 产物 jar; 无需修复的调用点时不重写。 */
    fun fix(jarFile: File, dexBytes: ByteArray) {
        val records = collectDexCtorSites(dexBytes)
        if (records.isEmpty()) return

        val existingCtors = HashMap<String, MutableSet<String>>()
        val superNames = HashMap<String, String>()

        // dex2jar 产物是 zip; ClassReader 吃的是单个类字节码, 先按 entry 拆开、两遍扫描后重打包
        val entries = LinkedHashMap<String, ByteArray>().also { map ->
            ZipFile(jarFile).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    if (!entry.isDirectory) {
                        map[entry.name] = zip.getInputStream(entry).use { it.readBytes() }
                    }
                }
            }
        }

        // pass1: 收集每个类的 super 与已有 <init> (仅扫描, 不产出)
        entries.values.forEach { bytes ->
            if (!bytes.isClassFile()) return@forEach
            val reader = ClassReader(bytes)
            reader.accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int,
                    access: Int,
                    name: String,
                    signature: String?,
                    superName: String,
                    interfaces: Array<out String>,
                ) {
                    superNames[name] = superName
                }

                override fun visitMethod(
                    access: Int,
                    name: String,
                    desc: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (name == "<init>") {
                        existingCtors.getOrPut(reader.className) { HashSet() }.add(desc)
                    }
                    return null
                }
            }, 0)
        }

        // pass2: 重写调用点 + 合成缺失构造器
        entries.replaceAll { _, bytes ->
            if (!bytes.isClassFile()) {
                bytes
            } else {
                val reader = ClassReader(bytes)
                val writer = object : ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
                    // 框架重算的公共父类查询按需加载类型; 解析失败兜底 Object, 不阻塞转换
                    override fun getCommonSuperClass(type1: String, type2: String): String =
                        runCatching { super.getCommonSuperClass(type1, type2) }.getOrDefault("java/lang/Object")
                }
                reader.accept(
                    FixingClassVisitor(writer, reader.className, records, existingCtors, superNames),
                    ClassReader.EXPAND_FRAMES,
                )
                writer.toByteArray()
            }
        }

        val rewritten = ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zipOut ->
                entries.forEach { (name, bytes) ->
                    zipOut.putNextEntry(ZipEntry(name))
                    zipOut.write(bytes)
                    zipOut.closeEntry()
                }
            }
            out.toByteArray()
        }
        jarFile.writeBytes(rewritten)
    }

    private fun ByteArray.isClassFile(): Boolean =
        size >= 4 && get(0) == 0xCA.toByte() && get(1) == 0xFE.toByte() && get(2) == 0xBA.toByte() && get(3) == 0xBE.toByte()

    private class FixingClassVisitor(
        classWriter: ClassWriter,
        private val className: String,
        private val records: Map<String, List<CtorSite>>,
        private val existingCtors: MutableMap<String, MutableSet<String>>,
        private val superNames: Map<String, String>,
    ) : ClassVisitor(Opcodes.ASM9, classWriter) {

        private val injectors = mutableListOf<() -> Unit>()

        override fun visitMethod(
            access: Int,
            name: String,
            desc: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor {
            val superVisitor = super.visitMethod(access, name, desc, signature, exceptions)
            val record = records["$className::$name::$desc"]
            if (record.isNullOrEmpty()) {
                return superVisitor
            }
            // 该方法的调用点修复: 按 owner 分组, 以 dex 序还原每个 NEW 的具体类型
            val byOwner = record.groupBy({ it.owner }, { it.concrete })
            val counters = HashMap<String, Int>()
            var lastNew: Pair<String, String>? = null // (抽象 owner, 映射后的具体类)
            return object : MethodVisitor(Opcodes.ASM9, superVisitor) {
                override fun visitTypeInsn(opcode: Int, type: String) {
                    if (opcode == Opcodes.NEW && type in byOwner) {
                        val ordinal = counters.merge(type, 1, Int::plus) ?: 1
                        val concrete = byOwner.getValue(type)[ordinal - 1]
                        lastNew = type to concrete
                        super.visitTypeInsn(opcode, concrete)
                        return
                    }
                    super.visitTypeInsn(opcode, type)
                }

                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    name: String,
                    desc: String,
                    isInterface: Boolean,
                ) {
                    val pending = lastNew
                    if (opcode == Opcodes.INVOKESPECIAL && name == "<init>" && pending != null && owner == pending.first) {
                        super.visitMethodInsn(opcode, pending.second, name, desc, isInterface)
                        lastNew = null
                        return
                    }
                    super.visitMethodInsn(opcode, owner, name, desc, isInterface)
                }
            }
        }

        override fun visitEnd() {
            // 合成缺失的转发构造器: X.<init>(ctorDesc) { aload_0; (压参) invokespecial owner.<init>(ctorDesc); return }
            // 反查: 本类作为 concrete 出现的全部 (ctorDesc, superOwner) 组合
            val ctors = existingCtors[className] ?: HashSet<String>().also { existingCtors[className] = it }
            records.values.flatten()
                .filter { it.concrete == className }
                .distinctBy { it.owner to it.ctorDesc }
                .forEach { site ->
                    val superOf = superNames[className]
                    if (superOf == site.owner && site.ctorDesc !in ctors) {
                        injectConstructor(site.owner, site.ctorDesc)
                        ctors.add(site.ctorDesc)
                    }
                }
            injectors.forEach { it() }
            super.visitEnd()
        }

        private fun injectConstructor(owner: String, desc: String) {
            injectors.add {
                val mv = cv.visitMethod(Opcodes.ACC_PUBLIC, "<init>", desc, null, null)
                mv.visitCode()
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                var slot = 1
                Type.getArgumentTypes(desc).forEach { argType ->
                    mv.visitVarInsn(argType.getOpcode(Opcodes.ILOAD), slot)
                    slot += argType.size
                }
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", desc, false)
                mv.visitInsn(Type.getReturnType(desc).getOpcode(Opcodes.IRETURN))
                mv.visitMaxs(0, 0)
                mv.visitEnd()
            }
        }
    }
}
