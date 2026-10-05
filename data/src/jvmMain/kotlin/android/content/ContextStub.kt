// android.* JVM stub (desktop 加载扩展 APK 用): 仅保证类解析与实例化, 不复刻真实行为。
// 范围依据: :data jvmMain 编译兼容层所需 + 测试 APK dex 引用面 + TVBox 壳类 (Path/Init) 引用面;
// 缺口按运行期报错按需补。
// 仅存在于 :data 的 jvm 变体 (data/src/jvmMain), :app (android 变体) 编译与打包不接触本目录。
package android.content

import io.legado.app.help.file.AppFilesDirs
import java.io.File

open class Context {

    open fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        SharedPrefsStub(name)

    /** TVBox 壳类 Path.cache()/files() 走这里; 归一到 AppFilesDirs 的对应目录。 */
    open fun getCacheDir(): File = File(AppFilesDirs.get().cacheDir)

    open fun getFilesDir(): File = File(AppFilesDirs.get().filesDir)

    /** 桌面无应用对象层级, 返回自身 (壳类与 jar 内透传用法拿到可用实例)。 */
    open fun getApplicationContext(): Context = this

    companion object {
        const val MODE_PRIVATE = 0
    }
}
