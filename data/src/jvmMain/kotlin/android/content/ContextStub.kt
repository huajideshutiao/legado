// android.* JVM stub (desktop 加载扩展 APK 用): 仅保证类解析与实例化, 不复刻真实行为。
// 范围依据: :data jvmMain 编译兼容层所需 + 测试 APK dex 引用面; 缺口按运行期报错按需补。
// 仅存在于 :data 的 jvm 变体 (data/src/jvmMain), :app (android 变体) 编译与打包不接触本目录。
package android.content

open class Context {

    open fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        SharedPrefsStub(name)

    companion object {
        const val MODE_PRIVATE = 0
    }
}
