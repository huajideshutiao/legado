package io.legado.app.utils

/**
 * URL/网络相关工具 expect/actual 门面。
 *
 * 原 jvmAndAndroidMain 件直接依赖 JDK API (java.net.URL/InetAddress/NetworkInterface,
 * java.util.BitSet, okhttp3.internal.publicsuffix.PublicSuffixDatabase), 进不了 commonMain;
 * 现下沉为 expect/actual object:
 * - commonMain 暴露 [NetworkUtils] object 与不泄漏 JDK 类型的方法签名 (encodedQuery/encodedForm/
 *   getAbsoluteURL(String?,String)/getBaseUrl/getSubDomain/getSubDomainOrNull/getDomain/
 *   isIPv4Address/isIPv6Address/isIPAddress), 调用处 `NetworkUtils.xxx(...)` 写法不变;
 * - jvmAndAndroidMain actual 用原 JDK 实现 (行为不变), 并保留两个附加成员:
 *   `getAbsoluteURL(URL?, String)` (参数为 java.net.URL) 与 `getLocalIPAddress(): List<InetAddress>`
 *   (返回类型为 java.net.InetAddress), 这两个签名 common 无 JDK 类型可见, 仅 jvmAndAndroid 暴露。
 *
 * 包名/对象名不变, 消费方 import 零改动。
 */
expect object NetworkUtils {

    /**
     * 支持JAVA的URLEncoder.encode出来的string做判断。 即: 将' '转成'+'
     * 0-9a-zA-Z保留 <br></br>
     * ! * ' ( ) ; : @ & = + $ , / ? # [ ] 保留
     * 其他字符转成%XX的格式，X是16进制的大写字符，范围是[0-9A-F]
     */
    fun encodedQuery(str: String): Boolean

    fun encodedForm(str: String): Boolean

    /**
     * 获取绝对地址
     *
     * baseURL 为空或 data url 时不拼接, 直接返回 relativePath.trim()
     * (三端一致: data url 无法作为相对拼接基址)。
     */
    fun getAbsoluteURL(baseURL: String?, relativePath: String): String

    /**
     * 获取绝对地址 (URL 重载)
     *
     * baseURL 为已解析的 [URL] 对象时直接按原版 java.net.URL 拼接语义处理,
     * 不经 String 门面 (门面会 substringBefore(",") 截断含逗号的 URL)。
     * jvmAndAndroidMain actual 即原版实现; nativeMain actual 用 [URL.toString]
     * 做手工拼接 (行为对齐)。AnalyzeRuleCore.getAbsoluteURL 默认实现走本重载,
     * 各端子类无需再 override。
     */
    fun getAbsoluteURL(baseURL: URL?, relativePath: String): String

    fun getBaseUrl(url: String?): String?

    /**
     * 获取域名，供cookie保存和读取，处理失败返回传入的url
     * http://1.2.3.4 => 1.2.3.4
     * https://www.example.com =>  example.com
     * http://www.biquge.com.cn => biquge.com.cn
     * http://www.content.example.com => example.com
     */
    fun getSubDomain(url: String): String

    fun getSubDomainOrNull(url: String): String?

    fun getDomain(url: String): String

    /**
     * Check if valid IPV4 address.
     *
     * @param input the address string to check for validity.
     * @return True if the input parameter is a valid IPv4 address.
     */
    fun isIPv4Address(input: String?): Boolean

    /**
     * Check if valid IPV6 address.
     */
    fun isIPv6Address(input: String?): Boolean

    /**
     * Check if valid IP address.
     */
    fun isIPAddress(input: String?): Boolean
}
