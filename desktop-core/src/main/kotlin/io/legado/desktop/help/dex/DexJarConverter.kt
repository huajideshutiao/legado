// dex 容器 → JVM 可加载 jar 的公共转换器 (桌面端): 漫画/视频扩展 (JvmExtensionLoader) 与
// TVBox spider jar (DesktopTvBoxHostPlatform) 共用。TVBox 生态 jar 是 zip/dex 容器
// (classes.dex [+ assets]), JVM 的 URLClassLoader 只认 class 文件, 必须经 dex2jar 转换。
package io.legado.desktop.help.dex

import com.googlecode.d2j.dex.Dex2jar
import com.googlecode.d2j.reader.MultiDexFileReader
import com.googlecode.dex2jar.tools.BaksmaliBaseDexExceptionHandler
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object DexJarConverter {

    /** zip 容器里 dex 条目名 (classes.dex / classes2.dex …)。 */
    val DEX_ENTRY_REGEX = Regex("""classes\d*\.dex""")

    /**
     * TVBox spider jar 入口: dex 容器 (zip 含 classes*.dex) / 裸 dex → 转换产物 jar
     * (多 dex 的 class 合并单产物, assets 等非 dex 条目原样保留, 供 jar 内自解包逻辑读取);
     * 已是 JVM class jar 或生态外形态 → 原样返回。产物缓存同目录 <name>-d2j.jar,
     * 原文件未比产物新 (mtime) 时复用。
     */
    fun jvmJarFor(dexContainer: File): File {
        val bytes = dexContainer.readBytes()
        if (!isDexFile(bytes) && !isZipFile(bytes)) return dexContainer
        val output = File(dexContainer.parentFile, "${dexContainer.nameWithoutExtension}-d2j.jar")
        if (isDexFile(bytes)) {
            return produce(output, dexContainer) { tmp -> convertDex(bytes, tmp) }
        }
        val (dexEntries, otherEntries) = readZipSplit(bytes)
        if (dexEntries.isEmpty()) return dexContainer
        return produce(output, dexContainer) { tmp -> mergeToJar(dexEntries, otherEntries, tmp) }
    }

    /**
     * 单 dex → JVM jar (参数面取自 Suwayomi-Server PackageTools.dex2jar, Dex2jarCmd 官方
     * 命令行同源), 随后按 dex 指令序还原 R8 接收者构造器调用点 (见 CtorSiteFixer)。
     */
    fun convertDex(dexBytes: ByteArray, jarFile: File) {
        val reader = MultiDexFileReader.open(dexBytes)
        Dex2jar.from(reader)
            .withExceptionHandler(BaksmaliBaseDexExceptionHandler())
            .reUseReg(false)
            .topoLogicalSort()
            .skipDebug(true)
            .optimizeSynchronized(false)
            .printIR(false)
            .noCode(false)
            .skipExceptions(false)
            .dontSanitizeNames(true)
            .computeFrames(true)
            .to(jarFile.toPath())
        check(jarFile.isFile) { "dex2jar 未产出 jar: ${jarFile.path}" }
        CtorSiteFixer.fix(jarFile, dexBytes)
    }

    private fun isDexFile(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x64.toByte() && bytes[1] == 0x65.toByte() &&
            bytes[2] == 0x78.toByte() && bytes[3] == 0x0A.toByte()

    private fun isZipFile(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

    /** zip 拆分: classes*.dex 条目与其余条目 (各自保持条目序)。 */
    private fun readZipSplit(bytes: ByteArray): Pair<LinkedHashMap<String, ByteArray>, LinkedHashMap<String, ByteArray>> {
        val dexEntries = LinkedHashMap<String, ByteArray>()
        val otherEntries = LinkedHashMap<String, ByteArray>()
        bytes.inputStream().use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) {
                        val content = zip.readBytes()
                        if (DEX_ENTRY_REGEX.matches(entry.name.substringAfterLast('/'))) {
                            dexEntries[entry.name] = content
                        } else {
                            otherEntries[entry.name] = content
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
        return dexEntries to otherEntries
    }

    /** 各 dex 独立转换后与原容器非 dex 条目合并成单产物 jar (重名条目以先写入者为准)。 */
    private fun mergeToJar(
        dexEntries: LinkedHashMap<String, ByteArray>,
        otherEntries: LinkedHashMap<String, ByteArray>,
        output: File,
    ) {
        val merged = LinkedHashMap<String, ByteArray>()
        dexEntries.values.forEachIndexed { index, dexBytes ->
            val part = File(output.parentFile, "${output.name}.$index.part")
            try {
                convertDex(dexBytes, part)
                readZip(part).forEach { (name, bytes) -> merged.putIfAbsent(name, bytes) }
            } finally {
                part.delete()
            }
        }
        otherEntries.forEach { (name, bytes) -> merged.putIfAbsent(name, bytes) }
        writeZip(output, merged)
    }

    /** 产物缓存判定 + 临时文件原子落地; 转换异常不残留半成品 (缓存住失败产物)。 */
    private fun produce(output: File, source: File, generate: (File) -> Unit): File {
        if (output.isFile && output.length() > 0 && output.lastModified() >= source.lastModified()) return output
        val tmp = File(output.parentFile, output.name + ".tmp")
        try {
            generate(tmp)
            if (!tmp.renameTo(output)) {
                tmp.copyTo(output, overwrite = true)
                tmp.delete()
            }
        } finally {
            if (tmp.isFile) tmp.delete()
        }
        return output
    }

    private fun readZip(file: File): LinkedHashMap<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipFile(file).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                if (!entry.isDirectory) {
                    entries[entry.name] = zip.getInputStream(entry).use { it.readBytes() }
                }
            }
        }
        return entries
    }

    private fun writeZip(file: File, entries: LinkedHashMap<String, ByteArray>) {
        val bytes = ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zipOut ->
                entries.forEach { (name, content) ->
                    zipOut.putNextEntry(ZipEntry(name))
                    zipOut.write(content)
                    zipOut.closeEntry()
                }
            }
            out.toByteArray()
        }
        file.writeBytes(bytes)
    }
}
