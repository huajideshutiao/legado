package io.legado.app.utils

import com.github.liuyueyi.quick.transfer.ChineseUtils as QuickChineseUtils
import com.github.liuyueyi.quick.transfer.Trie
import com.github.liuyueyi.quick.transfer.constants.TransType as QuickTransType
import com.github.liuyueyi.quick.transfer.dictionary.BasicDictionary
import com.github.liuyueyi.quick.transfer.dictionary.DictionaryContainer
import com.github.liuyueyi.quick.transfer.dictionary.DictionaryFactory
import java.io.File

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

/**
 * 简繁词典缓存文件定位器。宿主(app)启动早期注册,注入 RemoteAssetsUtils 的 tc 缓存路径
 * (缺失即后台拉取的副作用由宿主 lambda 内置)。未注册时 loadDict 直接放行,
 * 由 quick-transfer 懒加载自带默认词典(无缓存冷启动)。
 */
fun interface TcDictCachePathProvider {
    fun getPath(fileName: String): File
}

actual object ChineseUtils {

    private var fixed = false
    private val loadedSet = hashSetOf<TransType>()

    @Volatile
    private var pathProviderImpl: TcDictCachePathProvider? = null

    actual var pathProvider: Any?
        get() = pathProviderImpl
        set(value) {
            pathProviderImpl = value as? TcDictCachePathProvider
        }

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

    /**
     * commonMain TransType 映射到 quick-transfer TransType。
     * 用于 unLoad/loadDict/fixT2sDict 内部调用 quick-transfer API 时类型适配。
     */
    private fun TransType.toQuick(): QuickTransType = when (this) {
        TransType.SIMPLE_TO_TRADITIONAL -> QuickTransType.SIMPLE_TO_TRADITIONAL
        TransType.TRADITIONAL_TO_SIMPLE -> QuickTransType.TRADITIONAL_TO_SIMPLE
    }

    actual fun s2t(content: String): String {
        loadDict(TransType.SIMPLE_TO_TRADITIONAL)
        return QuickChineseUtils.s2t(content)
    }

    actual fun t2s(content: String): String {
        if (!fixed) fixT2sDict()
        loadDict(TransType.TRADITIONAL_TO_SIMPLE)
        return QuickChineseUtils.t2s(content)
    }

    actual fun unLoad(vararg transType: TransType) {
        QuickChineseUtils.unLoad(*transType.map { it.toQuick() }.toTypedArray())
        synchronized(loadedSet) {
            transType.forEach { loadedSet.remove(it) }
        }
    }

    actual fun fixT2sDict() {
        fixed = true
        QuickChineseUtils.loadExcludeDict(QuickTransType.TRADITIONAL_TO_SIMPLE, T2S_EXCLUDE_LIST)
    }

    actual fun loadDict(transType: TransType) {
        if (loadedSet.contains(transType)) return

        // 未注册缓存定位器: 放行让 quick-transfer 懒加载自带默认词典(无缓存冷启动)
        val provider = pathProviderImpl ?: return

        val fileNames = when (transType) {
            TransType.SIMPLE_TO_TRADITIONAL -> listOf("s2t.txt")
            TransType.TRADITIONAL_TO_SIMPLE -> listOf("t2s.txt", "t2hk.txt", "t2tw.txt")
        }

        // commonMain TransType 映射到 quick-transfer TransType, 用其 type 作为 map key
        val quickTransType = transType.toQuick()
        val charMap = HashMap<Char, Char>(8192)
        val trie = Trie<String>()
        var maxLen = 2
        var hasCached = false
        var allCached = true

        fileNames.forEach { fileName ->
            val file = provider.getPath(fileName)
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
            }
        }

        val map = getDictionaryMap()
        if (hasCached || !map.containsKey(quickTransType.type)) {
            val dictionary = BasicDictionary(quickTransType.type, charMap, trie, maxLen)
            map[quickTransType.type] = dictionary
            if (allCached && hasCached) {
                loadedSet.add(transType)
            }
        }
    }

}
