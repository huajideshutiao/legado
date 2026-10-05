// android.* JVM stub (桌面端 TVBox catvod 壳类用): 仅保证类解析与语义等价, 不复刻真实行为。
// 仅存在于 :data 的 jvm 变体 (data/src/jvmMain), :app (android 变体) 编译与打包不接触本目录。
package android.system

/** android.system.ErrnoException 的 JVM 等价 (仅承载函数名与消息, errno 取固定值)。 */
class ErrnoException(functionName: String?, errno: Int) : Exception("$functionName failed: errno=$errno") {

    val errno: Int = errno
}

/** android.system.Os 的 JVM 等价 (壳类 Path.move 只用到 rename)。 */
object Os {

    /** 对齐 rename(2) 语义: 目标已存在时失败, 不静默覆盖。 */
    fun rename(oldPath: String?, newPath: String?) {
        val source = java.nio.file.Paths.get(oldPath.orEmpty())
        val target = java.nio.file.Paths.get(newPath.orEmpty())
        try {
            java.nio.file.Files.move(source, target)
        } catch (e: java.io.IOException) {
            throw ErrnoException("rename", 1)
        }
    }
}
