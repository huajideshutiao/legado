package io.legado.app.utils

import io.legado.app.napi.OhosNativeBridge
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * 鸿蒙网络状态查询 (对照 Android ConnectivityManager / iOS SCNetworkReachability)。
 *
 * @ohos.net.connection 的 getDefaultNetSync / getConnectionPropertiesSync 仅 ArkTS API,
 * 经 [OhosNativeBridge] napi 桥同步查询 (NetworkBridgeHandler.ets):
 * - [isNetworkAvailable]: getDefaultNetSync 能取到默认网络句柄 (有可用网络)
 * - [isWifiConnect]: 连接属性 bearerType == BEARER_WIFI
 *
 * 降级策略: 桥未就绪 / 查询失败 / 超时返回 true —— 与 jvm 及历史行为一致
 * ("视为 wifi 不拦截"), 保证 napi 未接入阶段行为不变; 加载失败由调用方异常兜底。
 *
 * 短缓存: 书架网格每格封面加载都会查一次 (仅开"仅 WiFi 加载封面"时), 每格一次
 * napi 同步往返会串行排队在调用线程上; 3s TTL 让首屏只往返一次, 网络切换感知
 * 延迟可接受 (WiFi 下拦截本就不生效, 缓存命中无副作用)。
 */
@Serializable
private data class NetworkQueryResponse(
    val ok: Boolean,
    val network: Boolean = false,
    val wifi: Boolean = false,
    val error: String? = null,
)

private const val NETWORK_QUERY_CACHE_TTL_MS = 3000L

/** 最近一次成功查询结果 (弱一致即可: 并发下重复查询只是多一次往返, 不破坏正确性)。 */
@Volatile
private var cachedNetwork: NetworkQueryResponse? = null

/** 最近一次成功查询时间戳 (epoch ms)。 */
@Volatile
private var cachedNetworkAtMs: Long = 0L

@OptIn(ExperimentalTime::class)
private fun queryNetwork(): NetworkQueryResponse? {
    val now = Clock.System.now().toEpochMilliseconds()
    cachedNetwork?.let {
        if (now - cachedNetworkAtMs < NETWORK_QUERY_CACHE_TTL_MS) return it
    }
    if (!OhosNativeBridge.isNetworkBridgeReady()) return null
    val resultJson = OhosNativeBridge.invokeNetworkSync("query", "{}") ?: return null
    val resp = runCatching {
        KS_JSON.decodeFromString(NetworkQueryResponse.serializer(), resultJson)
    }.getOrNull() ?: return null
    if (resp.ok) {
        cachedNetwork = resp
        cachedNetworkAtMs = now
    }
    return resp
}

actual fun isNetworkAvailable(): Boolean = queryNetwork()?.network ?: true

actual fun isWifiConnect(): Boolean = queryNetwork()?.wifi ?: true
