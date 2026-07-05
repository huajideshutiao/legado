package io.legado.app.ui.config

import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.Toolbar
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.ItemThemeConfigBinding
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.noButton
import io.legado.app.lib.dialogs.yesButton
import io.legado.app.utils.GSON
import io.legado.app.utils.applyTint
import io.legado.app.utils.getClipText
import io.legado.app.utils.gone
import io.legado.app.utils.share
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.visible

class ThemeListDialog : BaseDialogFragment(0), Toolbar.OnMenuItemClickListener {

    override val isFullHeight: Boolean = true

    private lateinit var toolbar: Toolbar
    private lateinit var container: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            orientation = LinearLayout.VERTICAL
        }
        toolbar = createToolbar(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        root.addView(toolbar)
        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = false
        }
        val h = resources.getDimensionPixelSize(R.dimen.arco_spacing_lg)
        container = LinearLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            orientation = LinearLayout.VERTICAL
            setPadding(h, 0, h, 0)
        }
        scroll.addView(container)
        root.addView(scroll)
        return root
    }

    private fun createToolbar(ctx: Context): Toolbar {
        val tv = TypedValue()
        val themed = if (
            ctx.theme.resolveAttribute(androidx.appcompat.R.attr.actionBarStyle, tv, true)
            && tv.resourceId != 0
        ) ContextThemeWrapper(ctx, tv.resourceId) else ctx
        return Toolbar(themed).apply {
            elevation = 0f
            popupTheme = R.style.AppTheme_PopupOverlay
            setTitleTextAppearance(themed, R.style.ToolbarTitle)
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        toolbar.setTitle(R.string.theme_list)
        initMenu()
        initData()
        parentFragmentManager.setFragmentResultListener(
            ThemeCustomizeDialog.RESULT_CONFIG_CHANGED, this
        ) { _, _ -> initData() }
    }

    private fun initMenu() {
        toolbar.setOnMenuItemClickListener(this)
        toolbar.inflateMenu(R.menu.theme_list)
        toolbar.menu.applyTint(requireContext())
    }

    fun initData() {
        container.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        val builtins = ThemeConfig.getBuiltinConfigs(requireContext())
        val builtinCount = builtins.size
        (builtins + ThemeConfig.configList).forEachIndexed { position, item ->
            val itemBinding = ItemThemeConfigBinding.inflate(inflater, container, false)
            bindItem(itemBinding, item, position - builtinCount)
            container.addView(itemBinding.root)
        }
    }

    private fun bindItem(
        binding: ItemThemeConfigBinding,
        item: ThemeConfig.Config,
        configIndex: Int
    ) = binding.apply {
        tvName.text = item.themeName
        if (item.isBuiltin) {
            ivShare.gone()
            ivDelete.gone()
        } else {
            ivShare.visible()
            ivDelete.visible()
        }
        root.setOnClickListener {
            val ctx = requireContext()
            dismiss()
            if (item.isBuiltin) {
                ThemeConfig.applyBuiltin(ctx, item.isNightTheme)
            } else {
                ThemeConfig.applyConfig(ctx, item)
            }
        }
        root.setOnLongClickListener {
            if (item.isBuiltin) return@setOnLongClickListener false
            if (configIndex !in ThemeConfig.configList.indices) return@setOnLongClickListener false
            ThemeCustomizeDialog.editConfig(configIndex)
                .show(parentFragmentManager, "themeCustomize")
            true
        }
        ivShare.setOnClickListener { share(configIndex) }
        ivDelete.setOnClickListener { delete(configIndex) }
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_add -> alertNewTheme()
            R.id.menu_import -> {
                getClipText()?.let {
                    if (ThemeConfig.addConfig(it)) {
                        initData()
                    } else {
                        toastOnUi("格式不对,添加失败")
                    }
                }
            }
        }
        return true
    }

    private fun alertNewTheme() {
        ThemeCustomizeDialog.newConfig(isNight = false)
            .show(parentFragmentManager, "themeCustomize")
    }

    fun delete(index: Int) {
        alert(R.string.delete, R.string.sure_del) {
            yesButton {
                ThemeConfig.delConfig(index)
                initData()
            }
            noButton()
        }
    }

    fun share(index: Int) {
        val json = GSON.toJson(ThemeConfig.configList[index])
        requireContext().share(json, "主题分享")
    }
}
