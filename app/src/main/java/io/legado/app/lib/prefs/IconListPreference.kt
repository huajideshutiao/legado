package io.legado.app.lib.prefs

import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.fragment.app.FragmentActivity
import androidx.preference.ListPreference
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.ItemIconPreferenceBinding
import io.legado.app.utils.getCompatDrawable
import io.legado.app.utils.viewbindingdelegate.viewBinding


class IconListPreference(context: Context, attrs: AttributeSet) : ListPreference(context, attrs) {
    private var iconNames: Array<CharSequence>
    private val mEntryDrawables = arrayListOf<Drawable?>()

    init {
        layoutResource = R.layout.view_preference
        widgetLayoutResource = R.layout.view_icon

        val a = context.theme.obtainStyledAttributes(attrs, R.styleable.IconListPreference, 0, 0)

        iconNames = try {
            a.getTextArray(R.styleable.IconListPreference_icons)
        } finally {
            a.recycle()
        }

        // 初始化时一次性解析所有图标资源,避免列表滚动时重复查找
        for (iconName in iconNames) {
            val resId = context.resources
                .getIdentifier(iconName.toString(), "mipmap", context.packageName)
            val d = runCatching { context.getCompatDrawable(resId) }.getOrNull()
            mEntryDrawables.add(d)
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val v = Preference.bindView<ImageView>(
            context,
            holder,
            icon,
            title,
            summary,
            widgetLayoutResource,
            R.id.preview,
            50,
            50
        )
        if (v is ImageView) {
            val selectedIndex = findIndexOfValue(value)
            if (selectedIndex >= 0) {
                v.setImageDrawable(mEntryDrawables[selectedIndex])
            }
        }
    }

    override fun onClick() {
        getActivity()?.let {
            val dialog = IconDialog().apply {
                val args = Bundle()
                args.putString("value", value)
                args.putCharSequenceArray("entries", entries)
                args.putCharSequenceArray("entryValues", entryValues)
                args.putCharSequenceArray("iconNames", iconNames)
                arguments = args
                onChanged = { value ->
                    this@IconListPreference.value = value
                }
                // 传递预解析的 drawable,避免 Adapter 每次绑定时查找资源
                iconDrawables = this@IconListPreference.mEntryDrawables
            }
            it.supportFragmentManager
                .beginTransaction()
                .add(dialog, getFragmentTag())
                .commitAllowingStateLoss()
        }
    }

    override fun onAttached() {
        super.onAttached()
        val fragment =
            getActivity()?.supportFragmentManager?.findFragmentByTag(getFragmentTag()) as IconDialog?
        fragment?.let {
            it.onChanged = { value ->
                this@IconListPreference.value = value
            }
            // 旋转屏幕后 fragment 重建,IconListPreference 也重建并重新解析了 drawable,
            // 需要重新传递以确保 Adapter 使用最新的 drawable
            it.iconDrawables = mEntryDrawables
        }
    }

    private fun getActivity(): FragmentActivity? {
        val context = context
        if (context is FragmentActivity) {
            return context
        } else if (context is ContextWrapper) {
            val baseContext = context.baseContext
            if (baseContext is FragmentActivity) {
                return baseContext
            }
        }
        return null
    }

    private fun getFragmentTag(): String {
        return "icon_$key"
    }

    class IconDialog : BaseDialogFragment(R.layout.dialog_recycler_view) {

        var onChanged: ((value: String) -> Unit)? = null
        // 由 IconListPreference 在 onClick/onAttached 时赋值,
        // 复用预解析的 drawable 避免列表滚动时重复 getIdentifier
        var iconDrawables: List<Drawable?> = emptyList()
        private val binding by viewBinding(DialogRecyclerViewBinding::bind)

        override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
            binding.toolBar.setTitle(R.string.change_icon)
            binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
            val args = arguments ?: return
            val dialogValue = args.getString("value")
            val dialogEntries = args.getCharSequenceArray("entries")
            val dialogEntryValues = args.getCharSequenceArray("entryValues") ?: emptyArray()
            val adapter = Adapter(
                requireContext(),
                dialogEntries ?: emptyArray(),
                dialogEntryValues,
                dialogValue
            )
            adapter.setItems(dialogEntryValues.toList())
            binding.recyclerView.adapter = adapter
        }


        inner class Adapter(
            context: Context,
            private val dialogEntries: Array<CharSequence>,
            private val dialogEntryValues: Array<CharSequence>,
            private val dialogValue: String?
        ) : RecyclerAdapter<CharSequence, ItemIconPreferenceBinding>(context) {

            override fun getViewBinding(parent: ViewGroup): ItemIconPreferenceBinding {
                return ItemIconPreferenceBinding.inflate(inflater, parent, false)
            }

            override fun convert(
                holder: ItemViewHolder,
                binding: ItemIconPreferenceBinding,
                item: CharSequence,
                payloads: MutableList<Any>
            ) {
                binding.run {
                    val index = dialogEntryValues.indexOf(item)
                    if (index in dialogEntries.indices) {
                        label.text = dialogEntries[index]
                    }
                    // 直接复用预解析的 drawable,避免每次绑定都通过 getIdentifier 查找资源
                    if (index in this@IconDialog.iconDrawables.indices) {
                        icon.setImageDrawable(this@IconDialog.iconDrawables[index])
                    }
                    label.isChecked = item.toString() == dialogValue
                }
            }

            override fun registerListener(
                holder: ItemViewHolder,
                binding: ItemIconPreferenceBinding
            ) {
                // 统一在此处设置点击监听;之前 convert 中也设置了 root.setOnClickListener,
                // 由于 onBindViewHolder 调用顺序为 registerListener -> convert,后者会覆盖前者,
                // 导致此处的监听成为死代码。现统一只在 registerListener 中设置。
                holder.itemView.setOnClickListener {
                    getItem(holder.layoutPosition)?.let { item ->
                        onChanged?.invoke(item.toString())
                        this@IconDialog.dismissAllowingStateLoss()
                    }
                }
            }
        }
    }
}
