package com.github.catvod.utils

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.logging.Logger

/**
 * TVBox 壳 shell 执行工具: 签名与行为逐字对齐 FongMi catvod 模块的 utils.Shell。
 * 目前唯一调用方是 Path.create(File) 里的 Shell.exec("chmod 777 " + file)。
 * 日志走 JUL (与壳类其余日志通道一致)。
 */
object Shell {

    private val logger = Logger.getLogger("CatvodShell")

    @JvmStatic
    fun exec(command: String): String = try {
        val sb = StringBuilder()
        val p = Runtime.getRuntime().exec(command)
        val br = BufferedReader(InputStreamReader(p.inputStream))
        while (true) {
            val line = br.readLine() ?: break
            sb.append(line).append("\n")
        }
        logger.fine("Shell command '$command' with exit code '${p.waitFor()}'")
        Util.substring(sb.toString()) ?: ""
    } catch (e: Exception) {
        e.printStackTrace()
        ""
    }
}
