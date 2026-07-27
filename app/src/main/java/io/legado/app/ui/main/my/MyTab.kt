package io.legado.app.ui.main.my

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.LocalActivity
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.service.WebService
import io.legado.app.ui.about.AboutActivity
import io.legado.app.ui.about.ReadRecordActivity
import io.legado.app.ui.association.RuleSubActivity
import io.legado.app.ui.book.bookmark.AllBookmarkActivity
import io.legado.app.ui.book.filter.SourceFilterRuleActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.book.toc.rule.TxtTocRuleActivity
import io.legado.app.ui.compose.dialogs.selector
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.LocalEInk
import io.legado.app.ui.config.ConfigActivity
import io.legado.app.ui.config.ConfigTag
import io.legado.app.ui.dict.rule.DictRuleActivity
import io.legado.app.ui.replace.ReplaceRuleActivity
import io.legado.app.utils.FlowBus
import io.legado.app.utils.LogUtils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.openUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity

/**
 * 我的 tab(纯 Compose)：原 MyFragment(标题栏+菜单)与 MyPreferenceFragment(状态/副作用)上浮至此。
 * webService 开关态/summary 随 WEB_SERVICE 粘性事件回填；prefs 监听 RESUMED 期间注册，
 * webService 键 start/stop 服务、recordLog 刷新日志级别，与原 listener 一致。
 */
@Composable
fun MyTab() {
    val context = LocalContext.current
    val activity = LocalActivity.current as? AppCompatActivity
    val view = LocalView.current

    var webServiceChecked by remember {
        // 进入时以服务实际运行态校准 pref(复刻原 onCreateView)
        AppConfig.webService = WebService.isRun
        mutableStateOf(WebService.isRun)
    }
    var webServiceSummary by remember { mutableStateOf(webServiceSummaryText(context)) }

    // 复刻原 observeEventSticky<WEB_SERVICE>：服务状态变化实时回填开关态/地址
    LaunchedEffect(Unit) {
        FlowBus.withSticky(EventBus.WEB_SERVICE).collect {
            webServiceChecked = WebService.isRun
            webServiceSummary = webServiceSummaryText(context)
        }
    }
    // 原 onResume/onPause 注册的 OnSharedPreferenceChangeListener
    LifecycleResumeEffect(Unit) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                PreferKey.webService -> if (AppConfig.webService) {
                    WebService.start(context)
                } else {
                    WebService.stop(context)
                }

                "recordLog" -> LogUtils.upLevel()
            }
        }
        context.defaultSharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        onPauseOrDispose {
            context.defaultSharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    Column(Modifier.fillMaxSize()) {
        MyTitleBar(onHelp = { activity?.showHelp("appHelp") })
        MyConfigScreen(
            webServiceChecked = webServiceChecked,
            webServiceSummary = webServiceSummary,
            onThemeModeChange = {
                view.post { ThemeConfig.applyDayNight(context) }
            },
            // 开关切换后即时回填态；写 prefs 触发监听 start/stop，WEB_SERVICE 事件再校正
            onWebServiceChange = { webServiceChecked = it },
            onWebServiceLongClick = {
                val currentUrl = WebService.hostAddress
                context.selector(arrayListOf("复制地址", "浏览器打开")) { _, i ->
                    when (i) {
                        0 -> context.sendToClip(currentUrl)
                        1 -> context.openUrl(currentUrl)
                    }
                }
            },
            onThemeSetting = {
                context.startActivity<ConfigActivity> {
                    putExtra("configTag", ConfigTag.THEME_CONFIG)
                }
            },
            onWebDavSetting = {
                context.startActivity<ConfigActivity> {
                    putExtra("configTag", ConfigTag.BACKUP_CONFIG)
                }
            },
            onOtherSetting = {
                context.startActivity<ConfigActivity> {
                    putExtra("configTag", ConfigTag.OTHER_CONFIG)
                }
            },
            onBookSourceManage = { context.startActivity<BookSourceActivity>() },
            onReplaceManage = { context.startActivity<ReplaceRuleActivity>() },
            onSourceFilterRuleManage = { context.startActivity<SourceFilterRuleActivity>() },
            onTxtTocRuleManage = { context.startActivity<TxtTocRuleActivity>() },
            onDictRuleManage = { context.startActivity<DictRuleActivity>() },
            onRuleSubManage = { context.startActivity<RuleSubActivity>() },
            onBookmark = { context.startActivity<AllBookmarkActivity>() },
            onReadRecord = { context.startActivity<ReadRecordActivity>() },
            onAbout = { context.startActivity<AboutActivity>() },
        )
    }
}

private fun webServiceSummaryText(context: Context): String = if (WebService.isRun) {
    WebService.hostAddress
} else {
    context.getString(R.string.web_service_desc)
}

/** 我的页标题栏(替 fragment_my_config 的 TitleBar + main_my 菜单)：标题 + 帮助按钮，无返回箭头。 */
@Composable
private fun MyTitleBar(onHelp: () -> Unit) {
    val colors = AppTheme.colors
    val eInk = LocalEInk.current
    val hasBgImage = remember {
        !ThemeConfig.curBgImagePath.isNullOrBlank()
    }
    val bg = when {
        eInk -> Color.White
        hasBgImage -> Color.Transparent
        else -> colors.background
    }
    val insetsModifier = if (eInk) {
        Modifier.windowInsetsPadding(WindowInsets(0))
    } else {
        Modifier.statusBarsPadding()
    }
    Box(Modifier.fillMaxWidth().background(bg).then(insetsModifier)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.my),
                color = colors.primaryText,
                fontSize = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onHelp) {
                Icon(
                    painter = rememberPainter("ic_help"),
                    contentDescription = stringResource(R.string.help),
                    tint = colors.primaryText,
                )
            }
        }
        if (eInk) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.secondaryText.copy(alpha = 0.4f))
                    .align(Alignment.BottomStart),
            )
        }
    }
}
