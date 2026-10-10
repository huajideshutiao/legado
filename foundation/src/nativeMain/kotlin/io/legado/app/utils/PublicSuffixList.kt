package io.legado.app.utils

/**
 * 公共后缀 (Public Suffix List) 匹配器, 纯 Kotlin, iOS/鸿蒙共用 (nativeMain)。
 *
 * 对应 jvm/android 端的 okhttp 内部 API
 * `okhttp3.internal.publicsuffix.PublicSuffixDatabase.getEffectiveTldPlusOne(host)`;
 * iOS/鸿蒙无 okhttp 变体 (无 klib), 故匹配算法与数据表落在 nativeMain, 两端共用同一份实现。
 *
 * # 匹配规则 (与 okhttp 5.4.0 `PublicSuffixDatabase` 一致)
 * 1. 域名按 '.' 切 label, 去掉末尾空 label (允许末尾点);
 * 2. 精确匹配: 从最左 label 起逐段拼后缀查表, 首次命中即最长匹配;
 * 3. 通配匹配: 只用 '*' 替换最左 label (通配规则只出现在最左);
 * 4. 例外规则只在命中通配后参与, 例外规则自身持有 eTLD+1;
 * 5. 域名本身即公共后缀 (或只有一段, 如 `localhost`) 时没有 eTLD+1。
 *
 * # 匹配形式集中在本对象
 * 小写归一、精确/通配/例外/兜底选择与切片都在本对象内完成, 调用方只传原始域名,
 * 且匹配与切片统一使用 [lowercase] 后的 label, 同一域名的大小写写法结果一致。
 * (okhttp 用 `IDN.toUnicode` 变换后匹配、结果按入参原样切片: 纯 ASCII 标签不经 nameprep 也不小写,
 * 大写域名匹配不到全小写规则表会落到兜底规则, 如 `WWW.EXAMPLE.CO.UK` 得 `CO.UK`。)
 *
 * # 无法确定比对的输入一律按整主机
 * 表内规则为 Unicode 形式, 而 native 无 `java.net.IDN`: punycode 标签 (`xn--`) 无法与规则确定比对,
 * 含非 ASCII 字符的域名也无法保证与 IDN 归一后的形式一致 ([isMatchable] 为 false)。
 * 这类输入的 [normalize] 返回整主机, [isPublicSuffix] 返回 false ——
 * 宁可 cookie 只覆盖该主机, 不得放宽到公共后缀级。
 *
 * 可见性: :data 的 cookie 解析 (nativeMain) 也要用它拒绝公共后缀域名 (对齐 OkHttp `Cookie.parse`),
 * 跨模块无法用 internal, 故本对象保持 public; 数据表 [PublicSuffixRulesData] 仅本模块可见。
 */
object PublicSuffixList {

    /** okhttp 未命中任何规则时的兜底规则 (`PREVAILING_RULE`) => 取最后两段。 */
    private val PREVAILING_RULE = listOf("*")

    /** 例外规则标记 (okhttp `EXCEPTION_MARKER`)。 */
    private const val EXCEPTION_MARKER = '!'

    /** punycode 标签前缀 (RFC 3492 的 ACE 前缀)。 */
    private const val ACE_PREFIX = "xn--"

    /**
     * 规则集合。后缀在这里出现即命中; 通配规则以 `*.` 开头, 故按完整后缀串查表即可,
     * 无需给 `*` 单开一张表 (通配搜索即最长前缀通配命中)。
     *
     * 注: 本仓 `core.autocrlf=true`, 数据文件在部分机器上会以 CRLF 检出,
     * 故先去掉行尾 '\r' 再入集 —— 否则每条规则都带尾随 CR, 查表永远不中 (静默退化成兜底规则)。
     */
    private val rules: Set<String> by lazy {
        PublicSuffixRulesData.rulesText().split('\n').map { it.trimEnd('\r') }.toHashSet()
    }

    /** 例外规则集合 (原文不含 '!', 命中后按 okhttp 语义前置 '!')。 */
    private val exceptions: Set<String> by lazy {
        PublicSuffixRulesData.exceptionsText().split('\n').map { it.trimEnd('\r') }.toHashSet()
    }

    /**
     * 归一域名 (小写), 供 cookie 存取与来源分组: 可确定时返回 eTLD+1, 否则返回整主机。
     *
     * 返回整主机的四种情形: 无法确定比对 ([isMatchable] 为 false)、域名本身即公共后缀、
     * 输入含空 label、切片越界。
     */
    fun normalize(domain: String): String {
        return (getEffectiveTldPlusOne(domain) ?: domain).lowercase()
    }

    /**
     * 取 eTLD+1 (effective TLD plus one), 小写形式。
     *
     * @return eTLD+1; 无法确定比对、域名本身是公共后缀、或输入含空 label 时返回 null。
     */
    private fun getEffectiveTldPlusOne(domain: String): String? {
        val labels = matchableLabels(domain) ?: return null
        val rule = matchRule(labels)
        if (isPublicSuffixRule(labels, rule)) return null

        val firstLabelOffset = if (rule[0][0] == EXCEPTION_MARKER) {
            // 例外规则本身持有 eTLD+1
            labels.size - rule.size
        } else {
            // 普通规则是公共后缀, 须再多取一段
            labels.size - (rule.size + 1)
        }
        if (firstLabelOffset < 0 || firstLabelOffset >= labels.size) return null
        return labels.subList(firstLabelOffset, labels.size).joinToString(".")
    }

    /**
     * domain 自身是否为公共后缀 (对齐 OkHttp `Cookie.parse` 的拒绝条件
     * `getEffectiveTldPlusOne(domain) == null`)。
     *
     * 无法确定比对的输入返回 false: 这类域名的归一结果就是整主机, cookie 域不会因该判定放宽,
     * 而据此丢弃 cookie 会误伤合法 cookie。
     */
    fun isPublicSuffix(domain: String): Boolean {
        val labels = matchableLabels(domain) ?: return false
        return isPublicSuffixRule(labels, matchRule(labels))
    }

    /** 域名本身即公共后缀 (且非例外规则) 时没有 eTLD+1 (okhttp 同款判定)。 */
    private fun isPublicSuffixRule(labels: List<String>, rule: List<String>): Boolean {
        return labels.size == rule.size && rule[0][0] != EXCEPTION_MARKER
    }

    /**
     * 供规则比对的小写 label 列表; 无法确定比对时返回 null:
     * - 含 punycode 标签或非 ASCII 字符 (见类注释);
     * - 切分后出现空 label (`.com`、`a..b` 等; okhttp 对这类输入抛异常, 调用方回退整主机)。
     */
    private fun matchableLabels(domain: String): List<String>? {
        if (domain.isEmpty() || !isMatchable(domain)) return null
        val labels = splitDomain(domain.lowercase())
        if (labels.isEmpty() || labels.any { it.isEmpty() }) return null
        return labels
    }

    /**
     * 只有小写 ASCII 常规域名 (字母/数字/连字符/点) 且不含 punycode 标签时, 才能与表内规则确定比对。
     */
    private fun isMatchable(domain: String): Boolean {
        for (ch in domain) {
            if (ch !in 'a'..'z' && ch !in 'A'..'Z' && ch !in '0'..'9' && ch != '-' && ch != '.') {
                return false
            }
        }
        return splitDomain(domain).none { it.startsWith(ACE_PREFIX, ignoreCase = true) }
    }

    /**
     * okhttp `findMatchingRule`: 精确/通配/例外匹配, 未命中任何规则时用兜底规则。
     *
     * @return 规则 label; 例外规则以 [EXCEPTION_MARKER] 开头。
     */
    private fun matchRule(labels: List<String>): List<String> {
        // 1. 精确匹配: 从最左 label 起逐段缩短 (首次命中即最长匹配)
        var exact: String? = null
        for (i in labels.indices) {
            val candidate = labels.concatFrom(i)
            if (candidate in rules) {
                exact = candidate
                break
            }
        }

        // 2. 通配匹配: 只用 '*' 替换最左 label
        var wildcard: String? = null
        if (labels.size > 1) {
            val withWildcard = labels.toMutableList()
            for (labelIndex in 0 until labels.size - 1) {
                withWildcard[labelIndex] = "*"
                val candidate = withWildcard.concatFrom(labelIndex)
                if (candidate in rules) {
                    wildcard = candidate
                    break
                }
            }
        }

        // 3. 例外规则只在命中通配后参与
        var exception: String? = null
        if (wildcard != null) {
            for (labelIndex in 0 until labels.size - 1) {
                val candidate = labels.concatFrom(labelIndex)
                if (candidate in exceptions) {
                    exception = candidate
                    break
                }
            }
        }

        return when {
            exception != null -> "$EXCEPTION_MARKER$exception".split('.')
            exact == null && wildcard == null -> PREVAILING_RULE
            else -> {
                val exactLabels = exact?.split('.') ?: emptyList()
                val wildcardLabels = wildcard?.split('.') ?: emptyList()
                if (exactLabels.size > wildcardLabels.size) exactLabels else wildcardLabels
            }
        }
    }

    /** 域名按 '.' 切 label, 去掉末尾产生的空 label (允许末尾点, 与 okhttp splitDomain 一致)。 */
    private fun splitDomain(domain: String): List<String> {
        val labels = domain.split('.')
        return if (labels.lastOrNull() == "") labels.dropLast(1) else labels
    }

    /** 把 [from, size) 段用 '.' 拼回。 */
    private fun List<String>.concatFrom(from: Int): String {
        val sb = StringBuilder()
        for (i in from until size) {
            if (i > from) sb.append('.')
            sb.append(this[i])
        }
        return sb.toString()
    }
}
