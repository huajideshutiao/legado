package io.legado.app.ui.book.read.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.compose.component.AppSlider
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.platform.rememberString
import io.legado.app.ui.compose.theme.AppTheme
import java.util.Locale

/**
 * 自动翻页速度调节控制器：把 app 端 `ReadBookConfig.autoReadSpeed` 读写抽象为接口，
 * 由 app/desktop 端 thin wrapper 实现并桥接到各自配置存储。
 */
interface AutoReadController {
    /** 自动翻页速度（秒），对应 app 端 `ReadBookConfig.autoReadSpeed` */
    var autoReadSpeed: Int
}

/**
 * 自动翻页动作回调：把 app 端 `AutoReadDialog.CallBack` + `upTtsSpeechRate` 抽象为接口，
 * 由宿主实现并桥接到各自平台 API（app 端 Activity 跳转 + ReadAloud 服务，
 * desktop 端路由切换 + TtsEngine）。
 */
interface AutoReadActions {
    /** 打开目录列表（对应 app 端 `callBack.openChapterList`） */
    fun openChapterList()

    /** 显示主菜单（对应 app 端 `callBack.showMenuBar`） */
    fun showMenuBar()

    /** 停止自动翻页（对应 app 端 `callBack.autoPageStop`） */
    fun autoPageStop()

    /**
     * 显示翻页动画配置（对应 app 端 `BaseReadBookActivity.showPageAnimConfig`）。
     * app 端原回调内调 `upPageAnim()` + `ReadBook.loadContent(false)`，
     * 由宿主桥接到各自实现。
     */
    fun showPageAnimConfig()

    /**
     * 更新 TTS 语速（对应 app 端 `upTtsSpeechRate`）。
     * app 端原实现调 `ReadAloud.upTtsSpeechRate` + pause/resume，
     * 由宿主桥接到各自 TTS 引擎。
     */
    fun upTtsSpeechRate()
}

/**
 * 自动翻页控制面板：速度滑条 + 目录/菜单/停止/设置 4 按钮。
 *
 * 下沉自 app 端 `AutoReadDialog`，原 DialogFragment 宿主由各平台 thin wrapper 保留：
 * - `BaseReadBottomComposeDialog` 的 BottomSheet 形式（app 端）
 * - `AppAlertDialog` 的 content 槽（desktop 端）
 *
 * 资源访问替换：
 * - `stringResource(R.string.xxx)` → `rememberString("xxx")`
 * - `painterResource(R.drawable.xxx)` → `rememberPainter("xxx")`
 * - `ReadBookConfig.autoReadSpeed` → [controller.autoReadSpeed]
 * - `callBack.xxx` + `upTtsSpeechRate()` → [actions.xxx]
 *
 * 行为对齐原 AutoReadDialog：拖动滑条抬手写 controller + 调 [actions.upTtsSpeechRate]。
 *
 * @param controller 速度读写桥接
 * @param actions 动作回调桥接
 */
@Composable
fun AutoReadPanel(
    controller: AutoReadController,
    actions: AutoReadActions,
) {
    val colors = AppTheme.colors
    var speed by remember {
        mutableIntStateOf(controller.autoReadSpeed.coerceAtLeast(1))
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                rememberString("auto_page_speed"),
                color = colors.primaryText, fontSize = 14.sp,
                modifier = Modifier.weight(1f).padding(8.dp),
            )
            Text(
                String.format(Locale.ROOT, "%ds", speed),
                color = colors.primaryText, fontSize = 14.sp,
                modifier = Modifier.padding(8.dp),
            )
        }
        AppSlider(
            value = speed,
            min = 1,
            max = 120,
            onValueChange = { speed = it.coerceAtLeast(1) },
            onValueChangeFinished = {
                controller.autoReadSpeed = speed
                actions.upTtsSpeechRate()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ReadMenuIconButton("ic_toc", rememberString("chapter_list"), colors.primaryText) {
                actions.openChapterList()
            }
            ReadMenuIconButton("ic_menu", rememberString("main_menu"), colors.primaryText) {
                actions.showMenuBar()
            }
            ReadMenuIconButton("ic_auto_page_stop", rememberString("stop"), colors.primaryText) {
                actions.autoPageStop()
            }
            ReadMenuIconButton("ic_settings", rememberString("setting"), colors.primaryText) {
                actions.showPageAnimConfig()
            }
        }
    }
}

/**
 * 底部菜单图标按钮：60dp 宽竖排（图标 20dp + 文字 12sp），严格对齐 app 端
 * `BaseReadBottomComposeDialog.ReadMenuIconButton` 的尺寸（width=60dp, padding(vertical=8dp)）。
 */
@Composable
private fun ReadMenuIconButton(
    iconKey: String,
    text: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .width(60.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = rememberPainter(iconKey),
            contentDescription = text,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = text,
            color = tint,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
