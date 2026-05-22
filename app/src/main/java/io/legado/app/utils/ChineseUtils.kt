package io.legado.app.utils

import android.content.Context
import com.github.liuyueyi.quick.transfer.ChineseUtils
import com.github.liuyueyi.quick.transfer.Trie
import com.github.liuyueyi.quick.transfer.constants.TransType
import com.github.liuyueyi.quick.transfer.dictionary.BasicDictionary
import com.github.liuyueyi.quick.transfer.dictionary.DictionaryContainer
import com.github.liuyueyi.quick.transfer.dictionary.DictionaryFactory
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.items

private val T2S_EXCLUDE_LIST = listOf(
    "槃",
    "划槳", "列根", "雪梨", "雪糕", "多士", "起司", "芝士", "沙芬", "母音",
    "华乐", "民乐", "晶元", "晶片", "映像", "明覆", "明瞭", "新力", "新喻",
    "零錢", "零钱", "離線", "碟片", "模組", "桌球", "案頭", "機車", "電漿",
    "鳳梨", "魔戒", "載入", "菲林", "整合", "變數", "解碼", "散钱", "插水",
    "房屋", "房价", "快取", "德士", "建立", "常式", "席丹", "布殊", "布希",
    "巴哈", "巨集", "夜学", "向量", "半形", "加彭", "列印", "函式", "全形",
    "光碟", "介面", "乳酪", "沈船", "永珍", "演化", "牛油", "相容", "磁碟",
    "菲林", "規則", "酵素", "雷根", "饭盒",
    "路易斯", "非同步", "出租车", "周杰倫", "马铃薯", "馬鈴薯", "機械人", "電單車",
    "電扶梯", "音效卡", "飆車族", "點陣圖", "個入球", "顆進球", "沃尓沃", "晶片集",
    "斯瓦巴", "斜角巷", "战列舰", "快速面", "希特拉", "太空梭", "吐瓦魯", "吉布堤",
    "吉布地", "史太林", "南冰洋", "区域网", "波札那", "解析度", "酷洛米", "金夏沙",
    "魔獸紀元", "高空彈跳", "铁达尼号", "太空战士", "埃及妖后", "吉里巴斯", "附加元件",
    "魔鬼終結者", "純文字檔案", "奇幻魔法Melody", "列支敦斯登"
)

object ChineseUtils {

    private var fixed = false
    private val loadedSet = hashSetOf<TransType>()

    private val dictionaryMapField by lazy {
        DictionaryContainer::class.java.getDeclaredField("dictionaryMap").apply {
            isAccessible = true
        }
    }

    private val splitMethod by lazy {
        DictionaryFactory::class.java.getDeclaredMethod(
            "split", String::class.java, String::class.java
        ).apply { isAccessible = true }
    }

    @Suppress("UNCHECKED_CAST")
    private fun getDictionaryMap(): MutableMap<String, BasicDictionary> {
        return dictionaryMapField.get(DictionaryContainer.getInstance()) as MutableMap<String, BasicDictionary>
    }

    fun s2t(content: String): String {
        loadDict(TransType.SIMPLE_TO_TRADITIONAL)
        return ChineseUtils.s2t(content)
    }

    fun t2s(content: String): String {
        if (!fixed) fixT2sDict()
        loadDict(TransType.TRADITIONAL_TO_SIMPLE)
        return ChineseUtils.t2s(content)
    }

    fun unLoad(vararg transType: TransType) {
        ChineseUtils.unLoad(*transType)
        synchronized(loadedSet) {
            transType.forEach { loadedSet.remove(it) }
        }
    }

    fun fixT2sDict() {
        fixed = true
        ChineseUtils.loadExcludeDict(TransType.TRADITIONAL_TO_SIMPLE, T2S_EXCLUDE_LIST)
    }

    fun showConverterSelector(
        context: Context,
        onChanged: ((Int) -> Unit)? = null
    ) {
        context.alert(titleResource = R.string.chinese_converter) {
            items(context.resources.getStringArray(R.array.chinese_mode).toList()) { _, i ->
                if (AppConfig.chineseConverterType != i) {
                    AppConfig.chineseConverterType = i
                    onChanged?.invoke(i)
                    if (i > 0) {
                        when (i) {
                            1 -> loadDict(TransType.TRADITIONAL_TO_SIMPLE)
                            2 -> loadDict(TransType.SIMPLE_TO_TRADITIONAL)
                        }
                    }
                }
            }
        }
    }

    fun loadDict(transType: TransType) {
        if (loadedSet.contains(transType)) return

        val fileNames = when (transType) {
            TransType.SIMPLE_TO_TRADITIONAL -> listOf("s2t.txt")
            TransType.TRADITIONAL_TO_SIMPLE -> listOf("t2s.txt", "t2hk.txt", "t2tw.txt")
            else -> return
        }

        val charMap = HashMap<Char, Char>(8192)
        val trie = Trie<String>()
        var maxLen = 2
        var hasCached = false
        var allCached = true

        fileNames.forEach { fileName ->
            val file = RemoteAssetsUtils.getTcCachePath(fileName)
            if (file.exists() && file.length() > 0) {
                hasCached = true
                runCatching {
                    file.inputStream().bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (line.isNotEmpty() && !line.startsWith(DictionaryFactory.SHARP)) {
                                @Suppress("UNCHECKED_CAST")
                                val pair = splitMethod.invoke(
                                    null,
                                    line,
                                    DictionaryFactory.EQUAL
                                ) as Array<String>
                                if (pair.size >= 2) {
                                    val key = pair[0]
                                    val value = pair[1]
                                    if (key.length == 1 && value.length == 1) {
                                        charMap[key[0]] = value[0]
                                    } else {
                                        trie.add(key, value)
                                        if (key.length > maxLen) maxLen = key.length
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                allCached = false
                Coroutine.async { RemoteAssetsUtils.downloadTcIfNeeded(fileName) }
            }
        }

        val map = getDictionaryMap()
        if (hasCached || !map.containsKey(transType.type)) {
            val dictionary = BasicDictionary(transType.type, charMap, trie, maxLen)
            map[transType.type] = dictionary
            if (allCached && hasCached) {
                loadedSet.add(transType)
            }
        }
    }

}
