// TVBox spider jar (dex 容器) 桌面端 dex2jar 转换回归: TVBox 生态 jar 是 zip/dex 容器
// (classes.dex [+ assets]), URLClassLoader 只认 class 文件, 桌面端必须经 DexJarConverter
// 转换。样本运行时从 qist/tvbox 仓库获取并缓存 build/test-ext (与 JvmExtensionLoaderTest
// 同款模式); 断言口径为"类名可解析" (loadClass 不初始化, 不触发 jar 内静态副作用),
// 字节码有效性 (VerifyError 层) 由 CtorSiteFixer 在漫画链路的真实插件回归覆盖。
// fan.txt 型 "so 加密壳" 只验证转换层; 运行期解密依赖 Android native so, 桌面端不可用。
package io.legado.desktop.help.tvbox

import io.legado.desktop.TestNetwork
import io.legado.desktop.help.dex.DexJarConverter
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

class TvBoxJarDex2JarTest {

    companion object {

        private const val QIST_JAR_BASE = "https://raw.githubusercontent.com/qist/tvbox/master/jar/"
        private const val TOP98_JAR = "top98_1.jar"
        private const val FAN_TXT = "fan.txt"

        private val cacheDir = File("build/test-ext").apply { mkdirs() }

        private val http = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        private fun sample(name: String): File {
            val target = File(cacheDir, "tvbox-$name")
            if (!target.isFile || target.length() == 0L) {
                TestNetwork.requireSamples("TVBox 样本 $name")
                http.newCall(Request.Builder().url(QIST_JAR_BASE + name).build()).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}: ${QIST_JAR_BASE}$name" }
                    target.writeBytes(response.body.bytes())
                }
            }
            check(target.length() > 1024) { "样本下载不完整: ${target.path}" }
            return target
        }

        private fun classEntryNames(jar: File): List<String> =
            ZipFile(jar).use { zip ->
                zip.entries().asSequence()
                    .map { it.name }
                    .filter { it.endsWith(".class") }
                    .toList()
            }

        private fun assertClassesResolvable(jar: File) {
            val classes = classEntryNames(jar)
            assertTrue("产物应含 class 条目: ${jar.name}", classes.isNotEmpty())
            val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), TvBoxJarDex2JarTest::class.java.classLoader)
            classes.take(5).forEach { name ->
                loader.loadClass(name.removeSuffix(".class").replace('/', '.'))
            }
        }
    }

    @Test
    fun `未加密 dex 容器转换后类可经 URLClassLoader 解析`() {
        val src = sample(TOP98_JAR)
        val jar = DexJarConverter.jvmJarFor(src)
        assertNotEquals("dex 容器应被转换成新产物", src, jar)
        assertClassesResolvable(jar)
    }

    @Test
    fun `so 加密壳容器转换后引导层类可解析`() {
        val src = sample(FAN_TXT)
        val jar = DexJarConverter.jvmJarFor(src)
        assertNotEquals("dex 容器应被转换成新产物", src, jar)
        assertClassesResolvable(jar)
    }
}
