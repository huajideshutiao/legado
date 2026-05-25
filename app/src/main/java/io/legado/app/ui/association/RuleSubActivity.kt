package io.legado.app.ui.association

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.RuleSub
import io.legado.app.databinding.ActivityRuleSubBinding
import io.legado.app.databinding.DialogRuleSubEditBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.cancelButton
import io.legado.app.lib.dialogs.customView
import io.legado.app.lib.dialogs.noButton
import io.legado.app.lib.dialogs.okButton
import io.legado.app.lib.dialogs.yesButton
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 规则订阅管理界面
 */
class RuleSubActivity : VMBaseActivity<ActivityRuleSubBinding, RuleSubViewModel>(),
    RuleSubAdapter.CallBack {

    override val binding by viewBinding(ActivityRuleSubBinding::inflate)
    override val viewModel by viewModels<RuleSubViewModel>()
    private val adapter: RuleSubAdapter by lazy {
        RuleSubAdapter(this, this)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initView()
        initData()
    }

    private fun initView() {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.adapter = adapter
        val itemTouchCallback = ItemTouchCallback(adapter)
        itemTouchCallback.isCanDrag = true
        ItemTouchHelper(itemTouchCallback).attachToRecyclerView(binding.recyclerView)
    }

    private fun initData() {
        lifecycleScope.launch {
            appDb.ruleSubDao.flowAll().catch {
                AppLog.put("规则订阅界面获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect { ruleSubs ->
                binding.tvEmptyMsg.visible(ruleSubs.isEmpty())
                adapter.setItems(ruleSubs)
            }
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.rule_sub, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add -> showEditDialog(RuleSub())
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun openSubscription(ruleSub: RuleSub) {
        if (!ruleSub.url.isAbsUrl()) {
            toastOnUi(R.string.non_null_name_url)
            return
        }
        when (ruleSub.type) {
            0 -> showDialogFragment(ImportBookSourceDialog(ruleSub.url))
            1 -> showDialogFragment(ImportReplaceRuleDialog(ruleSub.url))
            2 -> showDialogFragment(ImportTxtTocRuleDialog(ruleSub.url))
            3 -> showDialogFragment(ImportDictRuleDialog(ruleSub.url))
            4 -> showDialogFragment(ImportHttpTtsDialog(ruleSub.url))
            else -> toastOnUi(R.string.error)
        }
    }

    override fun edit(ruleSub: RuleSub) {
        showEditDialog(ruleSub)
    }

    override fun delete(ruleSub: RuleSub) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + ruleSub.name)
            yesButton { viewModel.delete(ruleSub) }
            noButton()
        }
    }

    override fun toTop(ruleSub: RuleSub) {
        viewModel.toTop(ruleSub)
    }

    override fun toBottom(ruleSub: RuleSub) {
        viewModel.toBottom(ruleSub)
    }

    override fun upOrder(items: List<RuleSub>) {
        viewModel.upOrder(items)
    }

    @SuppressLint("InflateParams")
    private fun showEditDialog(ruleSub: RuleSub) {
        val title = if (ruleSub.name.isEmpty()) R.string.add else R.string.edit
        alert(titleResource = title) {
            val alertBinding = DialogRuleSubEditBinding.inflate(layoutInflater).apply {
                spType.setSelection(ruleSub.type.coerceIn(0, 4))
                etName.setText(ruleSub.name)
                etUrl.setText(ruleSub.url)
            }
            customView { alertBinding.root }
            okButton {
                val name = alertBinding.etName.text?.toString()?.trim().orEmpty()
                val url = alertBinding.etUrl.text?.toString()?.trim().orEmpty()
                if (name.isEmpty() || !url.isAbsUrl()) {
                    toastOnUi(R.string.non_null_name_url)
                    return@okButton
                }
                ruleSub.name = name
                ruleSub.url = url
                ruleSub.type = alertBinding.spType.selectedItemPosition
                viewModel.save(ruleSub)
            }
            cancelButton()
        }
    }

}
