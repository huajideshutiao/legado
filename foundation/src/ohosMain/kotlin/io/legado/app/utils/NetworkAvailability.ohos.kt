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
 *
 * 降级策略 (与 iOS 端统一, 依据 Android actual): Android 侧拿不到 ConnectivityManager /
 * activeNetwork / NetworkCapabilities 时一律 `return false` (fail-closed),
 * 故桥已就绪但查询/解析失败按 false;
 * 桥未接入时属"无查询能力", 同 desktop jvm 恒 true 放行, 保证 napi 未接入阶段行为不变。
 *
 * 短缓存: napi 同步查询有跨语言往返开销 (调用线程上串行排队), 3s TTL 让
 * 高频调用方 (如书架封面网格) 首屏只往返一次, 网络切换感知延迟可接受。
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

/**
 * [queryNetwork] 返回 null 时的兜底: 桥未接入 = 无查询能力, 放行 (同 desktop jvm 恒 true);
 * 桥已就绪却查询/解析失败则按 Android 语义 fail-closed 返回 false。
 */
private fun networkFallback(): Boolean = !OhosNativeBridge.isNetworkBridgeReady()

actual fun isNetworkAvailable(): Boolean = queryNetwork()?.network ?: networkFallback()
