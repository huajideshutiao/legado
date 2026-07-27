package io.legado.app.ui.book.search

import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.i18n.AppStringKey
import io.legado.app.help.i18n.appString
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 搜索范围
 *
 * 下沉说明:
 * - `androidx.lifecycle.MutableLiveData` → `kotlinx.coroutines.flow.MutableStateFlow`
 *   (stateLiveData 仅在 SearchScope 内部 postValue, 无外部 observe, 切换零行为影响);
 * - `appCtx.getString(R.string.all_source)` → `appString(AppStringKey.all_source)`;
 * - `AppConfig.searchScope/searchGroup` → `AppConfigProviders.get().searchScope/searchGroup`;
 * - `appDb.bookSourceDao` → `AppDbProviders.get().bookSourceDao`.
 */
data class SearchScope(private var scope: String) {

    constructor(groups: List<String>) : this(groups.joinToString(","))

    constructor(source: BookSource) : this(
        "${source.bookSourceName.replace(":", "")}::${source.bookSourceUrl}"
    )

    constructor(source: BookSourcePart) : this(
        "${source.bookSourceName.replace(":", "")}::${source.bookSourceUrl}"
    )

    override fun toString(): String {
        return scope
    }

    val stateLiveData = MutableStateFlow(scope)

    fun update(scope: String, postValue: Boolean = true) {
        this.scope = scope
        if (postValue) stateLiveData.value = scope
        save()
    }

    fun update(groups: List<String>) {
        scope = groups.joinToString(",")
        stateLiveData.value = scope
        save()
    }

    fun update(source: BookSource) {
        scope = "${source.bookSourceName}::${source.bookSourceUrl}"
        stateLiveData.value = scope
        save()
    }

    fun isSource(): Boolean {
        return scope.contains("::")
    }

    val display: String
        get() {
            if (scope.contains("::")) {
                return scope.substringBefore("::")
            }
            if (scope.isEmpty()) {
                return appString(AppStringKey.all_source)
            }
            return scope
        }

    /**
     * 搜索范围显示
     */
    val displayNames: List<String>
        get() {
            val list = arrayListOf<String>()
            if (scope.contains("::")) {
                list.add(scope.substringBefore("::"))
            } else {
                scope.splitNotBlank(",").forEach {
                    list.add(it)
                }
            }
            return list
        }

    fun remove(scope: String) {
        if (isSource()) {
            this.scope = ""
        } else {
            val stringBuilder = StringBuilder()
            this.scope.split(",").forEach {
                if (it != scope) {
                    if (stringBuilder.isNotEmpty()) {
                        stringBuilder.append(",")
                    }
                    stringBuilder.append(it)
                }
            }
            this.scope = stringBuilder.toString()
        }
        stateLiveData.value = this.scope
    }

    /**
     * 搜索范围书源
     */
    suspend fun getBookSources(): List<BookSource> {
        val list = hashSetOf<BookSource>()
        if (scope.isEmpty()) {
            list.addAll(AppDbProviders.get().bookSourceDao.allEnabled())
        } else {
            if (scope.contains("::")) {
                scope.substringAfter("::").let {
                    AppDbProviders.get().bookSourceDao.getBookSource(it)?.let { source ->
                        list.add(source)
                    }
                }
            } else {
                val oldScope = scope.splitNotBlank(",")
                val newScope = oldScope.filter {
                    val bookSources = AppDbProviders.get().bookSourceDao.getEnabledByGroup(it)
                    list.addAll(bookSources)
                    bookSources.isNotEmpty()
                }
                if (oldScope.size != newScope.size) {
                    update(newScope)
                    stateLiveData.value = scope
                }
            }
            if (list.isEmpty()) {
                scope = ""
                AppDbProviders.get().bookSourceDao.allEnabled().let {
                    if (it.isNotEmpty()) {
                        stateLiveData.value = scope
                        list.addAll(it)
                    }
                }
            }
        }
        return list.sortedBy { it.customOrder }
    }

    fun isAll(): Boolean {
        return scope.isEmpty()
    }

    fun save() {
        AppConfigProviders.get().searchScope = scope
        if (isAll() || isSource() || scope.contains(",")) {
            AppConfigProviders.get().searchGroup = ""
        } else {
            AppConfigProviders.get().searchGroup = scope
        }
    }

}
