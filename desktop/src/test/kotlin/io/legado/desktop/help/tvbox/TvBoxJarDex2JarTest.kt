// TVBox spider jar (dex 容器) 桌面端 dex2jar 转换回归: TVBox 生态 jar 是 zip/dex 容器
// (classes.dex [+ assets]), URLClassLoader 只认 class 文件, 桌面端必须经 DexJarConverter
// 转换。样本运行时从 qist/tvbox 仓库获取并缓存 build/test-ext (与 JvmExtensionLoaderTest
// 同款模式); 断言口径为"类名可解析" (loadClass 不初始化, 不触发 jar 内静态副作用),
// 字节码有效性 (VerifyError 层) 由 CtorSiteFixer 在漫画链路的真实插件回归覆盖。
// fan.txt 型 "so 加密壳" 只验证转换层; 运行期解密依赖 Android native so, 桌面端不可用。
package io.legado.desktop.help.tvbox

import io.legado.app.constant.AppLog
import io.legado.app.help.tvbox.TvBoxSite
import io.legado.app.model.tvbox.TvBoxManager
import io.legado.desktop.TestNetwork
import io.legado.desktop.help.dex.DexJarConverter
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

class TvBoxJarDex2JarTest {

    companion object {

        @JvmStatic
        @BeforeClass
        fun bootDesktopRuntime() = TvBoxTestRuntime.bootstrap()

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

    /**
     * 壳 jar 在桌面端的失败必须留痕且点出真实根因。
     *
     * 生态壳 jar 的自初始化 (com.github.catvod.spider.Init.init) 走 native 解密链, 引用 ART 专属类
     * (dalvik.system.DexClassLoader) 与 Android native so ⇒ 桌面 JVM 链接期即失败; 该失败原先被静默
     * 吞掉, 站点拖到很久以后才以与真因无关的形态 (壳 jar 自身 catch 对 null cause 调 getMessage 的
     * NPE) 爆出。断言留痕条目存在, 且其根因是"类缺失"而非壳 jar 的 NPE (日志比界面错误更接近真相)。
     */
    @Test
    fun `壳 jar 初始化失败留痕且根因是类缺失而非壳自身 NPE`() {
        val jar = sample(FAN_TXT)
        AppLog.clear()
        val site = TvBoxSite(
            key = "guard-probe",
            name = "guard-probe",
            type = 3,
            api = "csp_Tingshu275Guard",
            ext = "",
            jar = "",
            playUrl = "",
            searchable = true,
            filterable = true,
            quickSearch = false,
            timeoutSeconds = null,
            header = emptyMap(),
        )
        val error = runCatching {
            runBlocking { TvBoxManager.spiderFor(site, "file://${jar.absolutePath}") }
        }.exceptionOrNull()
        assertTrue("壳 jar 在桌面端应装载失败", error != null)

        val logged = AppLog.logs.firstOrNull { it.second.contains("Init.init 调用失败") }
        assertTrue("Init 初始化失败必须留痕, 实际日志: ${AppLog.logs.map { it.second }}", logged != null)
        val root = generateSequence<Throwable>(logged!!.third) { it.cause }.last()
        assertTrue(
            "留痕根因应是缺失类 (ClassNotFound/NoClassDefFound), 实际: $root",
            root is ClassNotFoundException || root is NoClassDefFoundError,
        )
    }
}
