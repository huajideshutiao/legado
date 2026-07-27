package io.legado.app.api.controller


import io.legado.app.api.ReturnData
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.BookSource
import io.legado.app.help.source.SourceHelp
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject

/**
 * 书源 CRUD Web 接口 (shared commonMain 下沉版)。
 *
 * 原 app 端实现仅依赖 DAO + SourceHelp + GSON 扩展, 这些均已下沉 commonMain:
 * - `appDb.bookSourceDao` → `AppDbProviders.get().bookSourceDao` (与 SourceHelp 同模式)
 * - [SourceHelp] 已整体下沉 commonMain
 * - [GSON]/[fromJsonObject]/[fromJsonArray] 已下沉 commonMain (kotlinx-serialization 兼容层)
 *
 * 行为与原 app 端逐字等价, 仅多一层 provider 间接。消费方 import 不变。
 */
object BookSourceController {

    suspend fun sources(): ReturnData {
        val bookSources = AppDbProviders.get().bookSourceDao.all()
        val returnData = ReturnData()
        return if (bookSources.isEmpty()) {
            returnData.setErrorMsg("设备源列表为空")
        } else returnData.setData(bookSources)
    }

    suspend fun saveSource(postData: String?): ReturnData {
        val returnData = ReturnData()
        postData ?: return returnData.setErrorMsg("数据不能为空")
        val bookSource = GSON.fromJsonObject<BookSource>(postData).getOrNull()
        if (bookSource != null) {
            if (bookSource.bookSourceName.isEmpty() || bookSource.bookSourceUrl.isEmpty()) {
                returnData.setErrorMsg("源名称和URL不能为空")
            } else {
                AppDbProviders.get().bookSourceDao.insert(bookSource)
                returnData.setData("")
            }
        } else {
            returnData.setErrorMsg("转换源失败")
        }
        return returnData
    }

    suspend fun saveSources(postData: String?): ReturnData {
        postData ?: return ReturnData().setErrorMsg("数据为空")
        val okSources = arrayListOf<BookSource>()
        val bookSources = GSON.fromJsonArray<BookSource>(postData).getOrNull()
        if (bookSources.isNullOrEmpty()) {
            return ReturnData().setErrorMsg("转换源失败")
        }
        bookSources.forEach { bookSource ->
            if (bookSource.bookSourceName.isNotBlank()
                && bookSource.bookSourceUrl.isNotBlank()
            ) {
                AppDbProviders.get().bookSourceDao.insert(bookSource)
                okSources.add(bookSource)
            }
        }
        return ReturnData().setData(okSources)
    }

    suspend fun getSource(parameters: Map<String, List<String>>): ReturnData {
        val url = parameters["url"]?.firstOrNull()
        val returnData = ReturnData()
        if (url.isNullOrEmpty()) {
            return returnData.setErrorMsg("参数url不能为空，请指定源地址")
        }
        val bookSource = AppDbProviders.get().bookSourceDao.getBookSource(url)
            ?: return returnData.setErrorMsg("未找到源，请检查书源地址")
        return returnData.setData(bookSource)
    }

    suspend fun deleteSources(postData: String?): ReturnData {
        kotlin.runCatching {
            GSON.fromJsonArray<BookSource>(postData).getOrThrow().let {
                SourceHelp.deleteBookSources(it)
            }
        }.onFailure {
            return ReturnData().setErrorMsg(it.message ?: "数据格式错误")
        }
        return ReturnData().setData("已执行"/*okSources*/)
    }
}
