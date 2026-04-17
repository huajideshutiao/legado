package io.legado.app.ui.association

import android.app.Application
import androidx.core.net.toUri
import androidx.lifecycle.MutableLiveData
import com.jayway.jsonpath.JsonPath
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.OldRssSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.source.SourceHelp
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.inputStream
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.isUri
import io.legado.app.utils.splitNotBlank


class ImportBookSourceViewModel(app: Application) : BaseViewModel(app) {
    var isAddGroup = false
    var groupName: String? = null
    val errorLiveData = MutableLiveData<String>()
    val successLiveData = MutableLiveData<Int>()

    val allSources = arrayListOf<BookSource>()
    val checkSources = arrayListOf<BookSourcePart?>()
    val selectStatus = arrayListOf<Boolean>()
    val newSourceStatus = arrayListOf<Boolean>()
    val updateSourceStatus = arrayListOf<Boolean>()

    val isSelectAll: Boolean get() = selectStatus.all { it }

    val isSelectAllNew: Boolean
        get() = newSourceStatus.indices.all { !newSourceStatus[it] || selectStatus[it] }

    val isSelectAllUpdate: Boolean
        get() = updateSourceStatus.indices.all { !updateSourceStatus[it] || selectStatus[it] }

    val selectCount: Int get() = selectStatus.count { it }

    fun importSelect(finally: () -> Unit) {
        execute {
            val group = groupName?.trim()
            val keepName = AppConfig.importKeepName
            val keepGroup = AppConfig.importKeepGroup
            val keepEnable = AppConfig.importKeepEnable
            val selectSource = arrayListOf<BookSource>()
            selectStatus.forEachIndexed { index, b ->
                if (b) {
                    val source = allSources[index]
                    checkSources[index]?.let {
                        if (keepName) source.bookSourceName = it.bookSourceName
                        if (keepGroup) source.bookSourceGroup = it.bookSourceGroup
                        if (keepEnable) {
                            source.enabled = it.enabled
                            source.enabledExplore = it.enabledExplore
                        }
                        source.customOrder = it.customOrder
                    }
                    if (!group.isNullOrEmpty()) {
                        source.bookSourceGroup = if (isAddGroup) {
                            val groups = linkedSetOf<String>()
                            source.bookSourceGroup?.splitNotBlank(AppPattern.splitGroupRegex)?.let {
                                groups.addAll(it)
                            }
                            groups.add(group)
                            groups.joinToString(",")
                        } else {
                            group
                        }
                    }
                    selectSource.add(source)
                }
            }
            SourceHelp.insertBookSource(*selectSource.toTypedArray())
            ContentProcessor.upReplaceRules()
        }.onFinally {
            finally.invoke()
        }
    }

    fun importSource(text: String) {
        execute {
            val mText = text.trim()
            when {
                mText.isAbsUrl() -> importSourceUrl(mText)
                mText.isUri() -> mText.toUri().inputStream(context).getOrThrow().use {
                    importFromJson(it.readBytes().toString(Charsets.UTF_8))
                }

                mText.isJsonObject() && mText.contains("sourceUrls") -> {
                    JsonPath.parse(mText).read<List<String>>("$.sourceUrls").forEach {
                        importSourceUrl(it)
                    }
                }

                mText.isJsonObject() || mText.isJsonArray() -> importFromJson(mText)
                else -> throw NoStackTraceException(context.getString(R.string.wrong_format))
            }
        }.onError {
            errorLiveData.postValue("ImportError:${it.localizedMessage}")
            AppLog.put("ImportError:${it.localizedMessage}", it)
        }.onSuccess {
            comparisonSource()
        }
    }

    private suspend fun importSourceUrl(url: String) {
        okHttpClient.newCallResponseBody {
            if (url.endsWith("#requestWithoutUA")) {
                url(url.substringBeforeLast("#requestWithoutUA"))
                header(AppConst.UA_NAME, "null")
            } else {
                url(url)
            }
        }.decompressed().byteStream().use {
            val json = it.readBytes().toString(Charsets.UTF_8)
            importFromJson(json)
        }
    }

    private fun importFromJson(json: String) {
        val isArray = json.isJsonArray()
        when {
            json.contains("bookSourceUrl") -> {
                if (isArray) {
                    allSources.addAll(GSON.fromJsonArray<BookSource>(json).getOrElse {
                        throw NoStackTraceException("不是书源")
                    })
                } else {
                    allSources.add(GSON.fromJsonObject<BookSource>(json).getOrElse {
                        throw NoStackTraceException("不是书源")
                    })
                }
            }

            json.contains("sourceUrl") -> {
                if (isArray) {
                    allSources.addAll(GSON.fromJsonArray<OldRssSource>(json).getOrElse {
                        throw NoStackTraceException("不是书源")
                    }.map { it.toBookSource() })
                } else {
                    allSources.add(GSON.fromJsonObject<OldRssSource>(json).getOrElse {
                        throw NoStackTraceException("不是书源")
                    }.toBookSource())
                }
            }
            else -> throw NoStackTraceException("不是书源")
        }
    }

    private fun comparisonSource() {
        execute {
            allSources.forEach {
                val source = appDb.bookSourceDao.getBookSourcePart(it.bookSourceUrl)
                checkSources.add(source)
                selectStatus.add(source == null || source.lastUpdateTime < it.lastUpdateTime)
                newSourceStatus.add(source == null)
                updateSourceStatus.add(source != null && source.lastUpdateTime < it.lastUpdateTime)
            }
            successLiveData.postValue(allSources.size)
        }
    }

}