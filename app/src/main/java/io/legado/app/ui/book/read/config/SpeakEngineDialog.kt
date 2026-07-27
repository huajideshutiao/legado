package io.legado.app.ui.book.read.config

import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.ui.association.ImportHttpTtsDialog
import io.legado.app.ui.compose.component.AppRadioButton
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.component.OverflowMenu
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.ui.login.showLoginDialog
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.toJson
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showExportSuccess
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking

/**
 * tts 引擎管理：系统引擎 + HttpTTS 列表单选，底部"书籍/通用"两种保存范围。
 */
class SpeakEngineDialog : BaseComposeDialogFragment() {

    override val isFullHeight: Boolean = true

    private val viewModel: SpeakEngineViewModel by viewModels()
    private val ttsUrlKey = "ttsUrlKey"
    private var ttsEngine: String? by mutableStateOf(ReadAloud.ttsEngine)
    private val callBack: CallBack? get() = parentFragment as? CallBack
    private val importDocResult by lazy {
        registerHandleFile { result ->
            result.uri?.let { uri ->
                showDialogFragment(ImportHttpTtsDialog(uri.toString()))
            }
        }
    }
    private val exportDirResult by lazy {
        registerHandleFile { result ->
            result.uri?.let { uri ->
                showExportSuccess(uri)
            }
        }
    }

    @Composable
    override fun Content() {
        val colors = AppTheme.colors
        var items by remember { mutableStateOf(emptyList<HttpTTS>()) }
        LaunchedEffect(Unit) {
            appDb.httpTTSDao.flowAll().catch {
                AppLog.put("朗读引擎界面获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect {
                items = it
            }
        }
        // 与原实现一致：选中值为 SelectItem json（系统）或 HttpTTS id 字符串
        val sysValue = GSON.fromJsonObject<SelectItem<String>>(ttsEngine).getOrNull()?.value

        Column(Modifier.fillMaxSize()) {
            DialogTitleBar(
                title = stringResource(R.string.speak_engine),
                onBack = { dismissAllowingStateLoss() },
                actions = {
                    IconButton(onClick = { showDialogFragment<HttpTtsEditDialog>() }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_add),
                            contentDescription = stringResource(R.string.add),
                            tint = colors.primaryText,
                        )
                    }
                    OverflowMenu { dismiss ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.import_default_rule)) },
                            onClick = { dismiss(); viewModel.importDefault() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.import_local)) },
                            onClick = {
                                dismiss()
                                importDocResult.launch {
                                    mode = HandleFileContract.FILE
                                    allowExtensions = arrayOf("txt", "json")
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.import_on_line)) },
                            onClick = { dismiss(); importAlert() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export)) },
                            onClick = {
                                dismiss()
                                exportDirResult.launch {
                                    mode = HandleFileContract.EXPORT
                                    fileData = HandleFileContract.FileData(
                                        "httpTts.json",
                                        GSON.toJson(items).toByteArray(),
                                        "application/json"
                                    )
                                }
                            },
                        )
                    }
                },
            )
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                item(key = "sysDefault") {
                    EngineRow(
                        name = "系统默认",
                        checked = ttsEngine == null || ttsEngine!!.isJsonObject()
                                && GSON.fromJsonObject<SelectItem<String>>(ttsEngine)
                            .getOrNull()?.value.isNullOrEmpty(),
                    ) { upTts(GSON.toJson(SelectItem("系统默认", ""))) }
                }
                items(viewModel.sysEngines.size, key = { "sys_${viewModel.sysEngines[it].name}" }) { i ->
                    val engine = viewModel.sysEngines[i]
                    EngineRow(
                        name = engine.label,
                        checked = sysValue == engine.name,
                    ) { upTts(GSON.toJson(SelectItem(engine.label, engine.name))) }
                }
                items(items.size, key = { items[it].id }) { i ->
                    val httpTTS = items[i]
                    EngineRow(
                        name = httpTTS.name,
                        checked = httpTTS.id.toString() == ttsEngine,
                        onEdit = { showDialogFragment(HttpTtsEditDialog(httpTTS.id)) },
                        onDelete = { runBlocking { appDb.httpTTSDao.delete(httpTTS) } },
                    ) {
                        upTts(httpTTS.id.toString())
                        if (httpTTS.hasLogin() && httpTTS.getLoginInfo().isNullOrBlank()) {
                            httpTTS.showLoginDialog(activity as AppCompatActivity)
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppTextButton(stringResource(R.string.book)) {
                    ReadBook.book?.let { it.config.ttsEngine = ttsEngine }
                    callBack?.upSpeakEngineSummary()
                    ReadAloud.upReadAloudClass()
                    dismissAllowingStateLoss()
                }
                Spacer(Modifier.weight(1f))
                AppTextButton(stringResource(R.string.cancel)) {
                    dismissAllowingStateLoss()
                }
                AppTextButton(stringResource(R.string.general)) {
                    ReadBook.book?.let { it.config.ttsEngine = null }
                    AppConfig.ttsEngine = ttsEngine
                    callBack?.upSpeakEngineSummary()
                    ReadAloud.upReadAloudClass()
                    dismissAllowingStateLoss()
                }
            }
        }
    }

    /** 引擎行：单选钮 + 名称，HttpTTS 项带编辑/删除 */
    @Composable
    private fun EngineRow(
        name: String,
        checked: Boolean,
        onEdit: (() -> Unit)? = null,
        onDelete: (() -> Unit)? = null,
        onSelect: () -> Unit,
    ) {
        val colors = AppTheme.colors
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clickable(onClick = onSelect),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppRadioButton(selected = checked, onClick = onSelect)
            Text(
                name, color = colors.primaryText, maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (onEdit != null) {
                IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_edit),
                        contentDescription = stringResource(R.string.edit),
                        tint = colors.primaryText,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_clear_all),
                        contentDescription = stringResource(R.string.delete),
                        tint = colors.primaryText,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }

    private fun upTts(tts: String) {
        ttsEngine = tts
    }

    private fun importAlert() {
        val aCache = ACache.get(cacheDir = false)
        val cacheUrls: MutableList<String> = aCache
            .getAsString(ttsUrlKey)
            ?.splitNotBlank(",")
            ?.toMutableList() ?: mutableListOf()
        alert(R.string.import_on_line) {
            val getUrl = editTextView(
                hint = "url",
                filterValues = cacheUrls,
                onDelete = {
                    cacheUrls.remove(it)
                    aCache.put(ttsUrlKey, cacheUrls.joinToString(","))
                },
            )
            okButton {
                getUrl().let { url ->
                    if (url.isAbsUrl() && !cacheUrls.contains(url)) {
                        cacheUrls.add(0, url)
                        aCache.put(ttsUrlKey, cacheUrls.joinToString(","))
                    }
                    showDialogFragment(ImportHttpTtsDialog(url))
                }
            }
        }
    }

    interface CallBack {
        fun upSpeakEngineSummary()
    }
}
