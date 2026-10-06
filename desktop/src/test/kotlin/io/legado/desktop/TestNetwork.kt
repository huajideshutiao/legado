package io.legado.desktop

import org.junit.Assume

/**
 * 联网样本测试开关。
 *
 * 扩展 APK / TVBox jar 样本是第三方产物, 不进仓库 (体积 + 再分发), 首次运行必须联网下载;
 * 无网络的离线/CI 镜像环境会因此红。故默认跳过需联网的用例 (JUnit 记为 skipped),
 * 需要真跑样本回归时显式开启: `-Dlegado.networkTests=true` 或 `LEGADO_NETWORK_TESTS=1`
 * (本地已有 build/test-ext 缓存时无需开启, 命中缓存不会走到本闸门)。
 */
object TestNetwork {

    val enabled: Boolean =
        System.getProperty("legado.networkTests") == "true" ||
            System.getenv("LEGADO_NETWORK_TESTS") == "1"

    fun requireSamples(what: String) {
        Assume.assumeTrue(
            "$what 需联网下载测试样本; 设 -Dlegado.networkTests=true 或 LEGADO_NETWORK_TESTS=1 启用",
            enabled,
        )
    }
}
