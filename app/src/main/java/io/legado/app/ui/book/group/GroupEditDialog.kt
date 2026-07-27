package io.legado.app.ui.book.group

import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookGroup
import io.legado.app.model.CoverRatio
import io.legado.app.ui.compose.component.AppCheckbox
import io.legado.app.ui.compose.component.AppDropdownMenu
import io.legado.app.ui.compose.component.AppOutlinedTextField
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.component.DialogTitleBar
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.platform.rememberPainter
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.ui.main.bookshelf.ShelfCover
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.inputStream
import io.legado.app.utils.readUri
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking

/**
 * 分组编辑: 封面/名称/排序/允许刷新, 编辑态可删除。
 */
class GroupEditDialog() : BaseComposeDialogFragment() {

    constructor(bookGroup: BookGroup? = null) : this() {
        arguments = Bundle().apply {
            // upGroup 整行写回, 传主键到达端重查, 避免快照冲掉并发修改
            bookGroup?.let { putLong("groupId", it.groupId) }
        }
    }

    private val viewModel by viewModels<GroupViewModel>()
    private var bookGroup: BookGroup? = null

    private var groupName by mutableStateOf("")
    private var bookSort by mutableIntStateOf(-1)
    private var enableRefresh by mutableStateOf(true)
    private var coverPath by mutableStateOf<String?>(null)

    private val selectImage by lazy {
        registerHandleFile { result ->
            result.uri ?: return@registerHandleFile
            readUri(result.uri) { fileDoc, inputStream ->
                try {
                    var file = requireContext().externalFiles
                    val suffix = fileDoc.name.substringAfterLast(".")
                    val fileName = result.uri.inputStream(requireContext()).getOrThrow().use { tmp ->
                        MD5Utils.md5Encode(tmp) + ".$suffix"
                    }
                    file = FileUtils.createFileIfNotExist(file, "covers", fileName)
                    FileOutputStream(file).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                    coverPath = file.absolutePath
                } catch (e: Exception) {
                    appCtx.toastOnUi(e.localizedMessage)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookGroup = arguments?.takeIf { it.containsKey("groupId") }?.let {
            runBlocking { appDb.bookGroupDao.getByID(it.getLong("groupId")) }
        }
        bookGroup?.let {
            groupName = it.groupName
            coverPath = it.cover
            // 越界排序值回落默认(对齐原 spinner count 校验)
            if (it.bookSort + 1 !in 0..6) {
                it.bookSort = -1
            }
            bookSort = it.bookSort
            enableRefresh = it.enableRefresh
        }
    }

    @Composable
    override fun Content() {
        val colors = AppTheme.colors
        Column(Modifier.fillMaxWidth()) {
            DialogTitleBar(
                title = stringResource(
                    if (bookGroup != null) R.string.group_edit else R.string.add_group
                ),
                onBack = { dismissAllowingStateLoss() },
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
            ) {
                Box(
                    Modifier
                        .width(110.dp)
                        .clickable {
                            selectImage.launch {
                                mode = HandleFileContract.IMAGE
                            }
                        },
                ) {
                    ShelfCover(
                        path = coverPath,
                        name = null,
                        author = null,
                        origin = null,
                        ratio = CoverRatio.NOVEL,
                        reloadKey = 0,
                        inBookshelf = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                ) {
                    AppOutlinedTextField(
                        value = groupName,
                        onValueChange = { groupName = it },
                        label = stringResource(R.string.group_name),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SortRow()
                    Row(
                        Modifier.clickable { enableRefresh = !enableRefresh },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppCheckbox(
                            checked = enableRefresh,
                            onCheckedChange = { enableRefresh = it },
                        )
                        Text(
                            text = stringResource(R.string.allow_drop_down_refresh),
                            color = colors.primaryText,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val showDelete = bookGroup
                    ?.let { it.groupId > 0 || it.groupId == Long.MIN_VALUE } == true
                if (showDelete) {
                    AppTextButton(text = stringResource(R.string.delete)) { deleteGroup() }
                }
                Spacer(Modifier.weight(1f))
                AppTextButton(text = stringResource(R.string.cancel)) { dismiss() }
                AppTextButton(text = stringResource(R.string.ok)) { onOk() }
            }
        }
    }

    /** 排序选择, 对照原 AppCompatSpinner(@array/book_sort, 选中位 = bookSort + 1) */
    @Composable
    private fun SortRow() {
        val colors = AppTheme.colors
        val sortEntries = stringArrayResource(R.array.book_sort)
        var sortMenu by remember { mutableStateOf(false) }
        Row(
            Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.sort),
                color = colors.accent,
                fontSize = 14.sp,
                modifier = Modifier.padding(4.dp),
            )
            Box {
                Row(
                    Modifier
                        .clickable { sortMenu = true }
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = sortEntries[bookSort + 1],
                        color = colors.primaryText,
                        fontSize = 14.sp,
                    )
                    Icon(
                        painter = rememberPainter("ic_arrow_drop_down"),
                        contentDescription = stringResource(R.string.sort),
                        tint = colors.secondaryText,
                        modifier = Modifier.size(24.dp),
                    )
                }
                AppDropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    sortEntries.forEachIndexed { index, entry ->
                        DropdownMenuItem(
                            onClick = { sortMenu = false; bookSort = index - 1 },
                        ) { Text(entry, color = colors.primaryText) }
                    }
                }
            }
        }
    }

    private fun deleteGroup() {
        alert(R.string.delete, R.string.sure_del) {
            yesButton {
                bookGroup?.let {
                    viewModel.delGroup(it) {
                        dismiss()
                    }
                }
            }
            noButton()
        }
    }

    private fun onOk() {
        val name = groupName
        if (name.isEmpty()) {
            toastOnUi("分组名称不能为空")
            return
        }
        bookGroup?.let {
            it.groupName = name
            it.cover = coverPath
            it.bookSort = bookSort
            it.enableRefresh = enableRefresh
            viewModel.upGroup(it) {
                dismiss()
            }
        } ?: viewModel.addGroup(name, bookSort, enableRefresh, coverPath) {
            dismiss()
        }
    }

}
