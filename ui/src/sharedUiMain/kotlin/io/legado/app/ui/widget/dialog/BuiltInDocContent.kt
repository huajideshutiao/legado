package io.legado.app.ui.widget.dialog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.legado.app.help.coroutine.IoDispatcher
import kotlinx.coroutines.withContext
import legado.ui.generated.resources.Res

/**
 * 内置文档正文: 读 composeResources `files/` 下的文档, 返回可直接交 [TextDialog]
 * (markdown = true) 渲染的正文 (四端同一取数路径)。
 *
 * 状态按 [path] 键控: 路径一变即回到空正文 (新正文到达前不残留上一篇), 空正文即"读取中"。
 * 读盘在 IO 线程、回主线程更新。
 *
 * 读取失败不降级成假正文: 这些文档随包分发, 读不到只可能是打包/改名错 (构建期事实),
 * 如实抛出比给用户看一段"读取失败"更有用。
 *
 * @param path composeResources `files/` 下的相对路径 (如 `web/help/md/dictRuleHelp.md`、
 *   `md/privacyPolicy.md`)
 */
@Composable
fun rememberBuiltInDocContent(path: String): String {
    var content by remember(path) { mutableStateOf("") }
    LaunchedEffect(path) {
        // 同步读文件不能卡主线程: 切 IO 线程读, 完成回主线程更新状态
        content = withContext(IoDispatcher) { Res.readBytes("files/$path").decodeToString() }
    }
    return content
}
