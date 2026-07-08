package io.legado.app.base.adapter

import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import io.legado.app.lib.theme.ThemeInterceptor

/**
 * Created by Invincible on 2017/11/28.
 *
 * 所有 RecyclerAdapter / DiffRecyclerAdapter 子类的 ViewHolder 都经过此处构造,
 * 在此对 itemView 调用 ThemeInterceptor.applyToTree 统一着色:
 * - inflate 的 item View 已被 Factory2 着色并标记, applyToTree 遍历时零成本跳过。
 * - 代码动态构造的 item View (如 BookmarkAdapter 直接 new TextView) 不走 Factory2,
 *   applyToTree 在此兜底着色。
 *
 * holder 复用时 (RecyclerView 复用机制) root 已标记, applyToTree 立即返回, 无重复开销。
 */
class ItemViewHolder(val binding: ViewBinding) : RecyclerView.ViewHolder(binding.root) {

    init {
        ThemeInterceptor.applyToTree(binding.root)
    }
}