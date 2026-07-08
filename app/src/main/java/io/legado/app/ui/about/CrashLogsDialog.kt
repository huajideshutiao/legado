package io.legado.app.ui.about

import android.app.Application
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import androidx.fragment.app.viewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.BaseViewModel
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.ThemeUtils
import io.legado.app.lib.theme.space
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.delete
import io.legado.app.utils.find
import io.legado.app.utils.getFile
import io.legado.app.utils.list
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.isActive

class CrashLogsDialog : BaseDialogFragment(R.layout.dialog_recycler_view),
    Toolbar.OnMenuItemClickListener {

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val viewModel by viewModels<CrashViewModel>()
    private val adapter by lazy { LogAdapter() }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setupTitleBar(
            title = getString(R.string.crash_log),
            menuRes = R.menu.crash_log,
            onMenuClick = ::onMenuItemClick
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        viewModel.logLiveData.observe(viewLifecycleOwner) {
            adapter.setItems(it)
        }
        viewModel.initData()
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_clear -> viewModel.clearCrashLog()
        }
        return true
    }

    private fun showLogFile(fileDoc: FileDoc) {
        viewModel.readFile(fileDoc) {
            if (lifecycleScope.isActive) {
                showDialogFragment(TextDialog(fileDoc.name, it))
            }
        }

    }

    class TextItemBinding(val root: TextView) : ViewBinding {
        override fun getRoot(): View = root
    }

    inner class LogAdapter : RecyclerAdapter<FileDoc, TextItemBinding>(requireContext()) {

        override fun getViewBinding(parent: ViewGroup): TextItemBinding {
            val tv = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(
                    parent.context.space.default,
                    parent.context.space.default,
                    parent.context.space.default,
                    parent.context.space.default
                )
                // 点击打开日志文件,需有 ripple 反馈
                background = ThemeUtils.resolveDrawable(
                    parent.context, android.R.attr.selectableItemBackground
                )
                isSingleLine = true
                setTextColor(parent.context.getColor(R.color.primaryText))
            }
            return TextItemBinding(tv)
        }

        override fun registerListener(holder: ItemViewHolder, binding: TextItemBinding) {
            binding.root.setOnClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let { item ->
                    showLogFile(item)
                }
            }
            binding.root.setOnLongClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let { item ->
                    item.asFile()?.let {
                        requireContext().share(it, title = getString(R.string.share))
                    } ?: requireContext().share(item.uri, title = getString(R.string.share))
                }
                true
            }
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: TextItemBinding,
            item: FileDoc,
            payloads: MutableList<Any>
        ) {
            binding.root.text = item.name
        }

    }

    class CrashViewModel(application: Application) : BaseViewModel(application) {

        val logLiveData = MutableLiveData<List<FileDoc>>()

        fun initData() {
            execute {
                val list = arrayListOf<FileDoc>()
                context.externalCacheDir?.getFile("crash")?.listFiles { it.isFile }?.forEach {
                        list.add(FileDoc.fromFile(it))
                    }
                val backupPath = AppConfig.backupPath
                if (!backupPath.isNullOrEmpty()) {
                    val uri = backupPath.toUri()
                    FileDoc.fromUri(uri, true).find("crash")?.list {
                            !it.isDir
                        }?.let {
                            list.addAll(it)
                        }
                }
                return@execute list.sortedByDescending { it.name }.distinctBy { it.name }
            }.onSuccess {
                logLiveData.postValue(it)
            }
        }

        fun readFile(fileDoc: FileDoc, success: (String) -> Unit) {
            execute {
                String(fileDoc.readBytes())
            }.onSuccess {
                success.invoke(it)
            }.onError {
                context.toastOnUi(it.localizedMessage)
            }
        }

        fun clearCrashLog() {
            execute {
                context.externalCacheDir?.getFile("crash")?.let {
                        FileUtils.delete(it, false)
                    }
                val backupPath = AppConfig.backupPath
                if (!backupPath.isNullOrEmpty()) {
                    val uri = backupPath.toUri()
                    FileDoc.fromUri(uri, true).find("crash")?.delete()
                }
            }.onError {
                context.toastOnUi(it.localizedMessage)
            }.onFinally {
                initData()
            }
        }

    }

}
