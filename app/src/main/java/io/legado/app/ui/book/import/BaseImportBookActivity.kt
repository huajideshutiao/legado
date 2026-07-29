package io.legado.app.ui.book.import

import androidx.activity.addCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.config.AppConfig
import io.legado.app.model.fileBook.FileBook
import io.legado.app.model.fileBook.importFromArchive
import io.legado.app.ui.compose.dialogs.alert
import io.legado.app.ui.compose.dialogs.selector
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileDoc
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.toReadRoute
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 本地/远程导入界面共用骨架(纯 Compose)：面包屑/加载条/搜索关键字状态 + 书籍保存位置 + 压缩包处理。
 */
abstract class BaseImportBookActivity : BaseComposeActivity() {

    private var localBookTreeSelectListener: ((Boolean) -> Unit)? = null

    /** 面包屑路径，null 时不显示 */
    var path by mutableStateOf<String?>(null)
        private set

    /** 顶部 2dp 加载条 */
    var loading by mutableStateOf(false)
        protected set

    /** 标题栏搜索框关键字 */
    var searchKey by mutableStateOf("")
        private set

    val localBookTreeSelect = registerHandleFile {
        it.uri?.let { treeUri ->
            AppConfig.defaultBookTreeUri = treeUri.toString()
            localBookTreeSelectListener?.invoke(true)
        } ?: localBookTreeSelectListener?.invoke(false)
    }

    fun upSearchKey(key: String) {
        searchKey = key
        onSearchTextChange(key)
    }

    /**
     * 设置返回键行为
     */
    protected fun setupBackPress(onBack: () -> Boolean) {
        onBackPressedDispatcher.addCallback(this) {
            if (!onBack()) {
                finish()
            }
        }
    }

    /**
     * 显示面包屑路径
     */
    protected fun showBreadcrumb(path: String) {
        this.path = path
    }

    /**
     * 设置书籍保存位置
     */
    protected suspend fun setBookStorage() = suspendCancellableCoroutine sc@{ block ->
        localBookTreeSelectListener = {
            localBookTreeSelectListener = null
            block.resume(it)
        }
        //测试书籍保存位置是否设置
        if (!AppConfig.defaultBookTreeUri.isNullOrBlank()) {
            localBookTreeSelectListener = null
            block.resume(true)
            return@sc
        }
        //测试读写??
        val storageHelp =
            "* 由于安卓的存储访问限制，阅读需要设置**公共目录下的子目录**来实现书籍拷贝、下载，例如Documents/Books、Download/Books\n" +
                "* 如不设置，将无法正常使用本地书籍、webDav书籍的相关功能"
        val hint = getString(R.string.select_book_folder)
        alert(hint, storageHelp) {
            okButton {
                localBookTreeSelect.launch {
                    title = hint
                }
            }
            cancelButton {
                localBookTreeSelectListener = null
                block.resume(false)
            }
            onCancelled {
                localBookTreeSelectListener = null
                block.resume(false)
            }
        }
    }

    abstract fun onSearchTextChange(newText: String?)

    protected fun startReadBook(book: Book) {
        // 优先走统一导航，缺失实现时回退到原 startActivity 方式
        val navigator = AppNavigatorProviders.getOrNull()
        if (navigator != null) {
            navigator.push(book.toReadRoute())
            finish()
        } else {
            startActivityForBook(book)
        }
    }

    protected fun onArchiveFileClick(fileDoc: FileDoc) {
        val fileNames = ArchiveUtils.getArchiveFilesName(fileDoc) {
            it.matches(AppPattern.bookFileRegex)
        }
        if (fileNames.size == 1) {
            val name = fileNames[0]
            runBlocking { appDb.bookDao.getBookByFileName(name) }?.let {
                startReadBook(it)
            } ?: showImportAlert(fileDoc, name)
        } else {
            showSelectBookReadAlert(fileDoc, fileNames)
        }
    }

    private fun showSelectBookReadAlert(fileDoc: FileDoc, fileNames: List<String>) {
        if (fileNames.isEmpty()) {
            toastOnUi(R.string.unsupport_archivefile_entry)
            return
        }
        selector(
            R.string.start_read,
            fileNames
        ) { _, name, _ ->
            runBlocking { appDb.bookDao.getBookByFileName(name) }?.let {
                startReadBook(it)
            } ?: showImportAlert(fileDoc, name)
        }
    }

    /* 添加压缩包内指定文件到书架 */
    private inline fun addArchiveToBookShelf(
        fileDoc: FileDoc,
        fileName: String,
        onSuccess: (Book) -> Unit
    ) {
        FileBook.importFromArchive(fileDoc.uri, fileName) {
            it.contains(fileName)
        }.firstOrNull()?.run {
            onSuccess.invoke(this)
        }
    }

    /* 提示是否重新导入所点击的压缩文件 */
    private fun showImportAlert(fileDoc: FileDoc, fileName: String) {
        alert(
            R.string.draw,
            R.string.no_book_found_bookshelf
        ) {
            okButton {
                addArchiveToBookShelf(fileDoc, fileName) {
                    startReadBook(it)
                }
            }
            noButton()
        }
    }

}
