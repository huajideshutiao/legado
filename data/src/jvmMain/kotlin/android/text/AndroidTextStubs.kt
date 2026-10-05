// android.* JVM stub (桌面端 TVBox catvod 壳类用): 仅保证类解析与语义等价, 不复刻真实行为。
// 仅存在于 :data 的 jvm 变体 (data/src/jvmMain), :app (android 变体) 编译与打包不接触本目录。
package android.text

/** android.text.TextUtils 的 JVM 等价 (壳类只用到 isEmpty / join 两面)。 */
object TextUtils {

    fun isEmpty(str: CharSequence?): Boolean = str == null || str.length == 0

    /** 对齐 Android TextUtils.join(CharSequence, Iterable): null 元素输出空串。 */
    fun join(delimiter: CharSequence, tokens: Iterable<*>): String =
        tokens.joinTo(StringBuilder(), delimiter) { it?.toString().orEmpty() }.toString()

    fun join(delimiter: CharSequence, array: Array<*>): String =
        array.joinTo(StringBuilder(), delimiter) { it?.toString().orEmpty() }.toString()
}
