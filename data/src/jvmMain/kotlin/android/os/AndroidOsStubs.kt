// android.* JVM stub (桌面端 TVBox catvod 壳类用): 仅保证类解析与语义等价, 不复刻真实行为。
// 仅存在于 :data 的 jvm 变体 (data/src/jvmMain), :app (android 变体) 编译与打包不接触本目录。
package android.os

import io.legado.app.help.file.AppFilesDirs
import java.io.File

/**
 * android.os.Environment 的 JVM 等价: getExternalStorageDirectory 归一到
 * [AppFilesDirs] 的 filesDir (桌面无外部存储概念, 壳类 Path.root()/tv()/backup()
 * 的 jar 面目录随之落在应用数据目录内)。
 */
object Environment {

    fun getExternalStorageDirectory(): File = File(AppFilesDirs.get().filesDir)

    fun getExternalStorageState(): String = "mounted"
}

/** android.os.StatFs 的 JVM 等价: 块大小取 1, 可用块即剩余字节 (乘积仍为真实剩余空间)。 */
class StatFs(path: String) {

    private val usableSpace: Long = File(path).usableSpace

    fun getAvailableBlocksLong(): Long = usableSpace

    fun getBlockSizeLong(): Long = 1

    @Suppress("unused")
    fun getAvailableBytes(): Long = usableSpace
}
