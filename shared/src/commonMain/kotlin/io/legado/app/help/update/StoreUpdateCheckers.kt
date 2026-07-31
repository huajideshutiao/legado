package io.legado.app.help.update

/**
 * 商店渠道检测实现 (预留骨架, 上架后启用)。
 *
 * 现状: 本项目不上架任何应用市场, iOS/鸿蒙都靠 GitHub release 侧载,
 * 所以两个实现只落接口 + TODO, 不接网络。上架后在 [UpdateStrategies] 换绑即可,
 * 检测层以外的代码不动。
 */

/**
 * App Store 检测 (iTunes Lookup API)。
 *
 * 上架后实现: GET `https://itunes.apple.com/lookup?bundleId=<id>&country=<region>`,
 * 取 `results[0].version` / `releaseNotes` / `trackViewUrl`,
 * [UpdateCheckInfo.landingUrl] 填 `itms-apps://itunes.apple.com/app/id<trackId>` 直接拉起 App Store。
 * 苹果审核禁止应用内自更新, 所以 downloadUrl 恒为空, 执行层配 [UpdateAction.OPEN_STORE]。
 */
class AppStoreChecker(
    private val bundleId: String,
    private val region: String = "cn",
) : UpdateChecker {

    override suspend fun check(request: UpdateCheckRequest): UpdateCheckResult {
        // TODO 上架 App Store 后实现 iTunes Lookup 查询
        return UpdateCheckResult.Failed(
            NotImplementedError("AppStoreChecker 未启用 (bundleId=$bundleId, region=$region)")
        )
    }
}

/**
 * 华为应用市场检测 (AppGallery)。
 *
 * 上架后实现: 走 AppGallery Connect 的版本查询接口 (需 client credentials),
 * [UpdateCheckInfo.landingUrl] 填 `store://appgallery.huawei.com/app/detail?id=<pkg>`。
 * 同样禁止应用内自更新, 执行层配 [UpdateAction.OPEN_STORE]。
 */
class AppGalleryChecker(
    private val packageName: String,
) : UpdateChecker {

    override suspend fun check(request: UpdateCheckRequest): UpdateCheckResult {
        // TODO 上架华为应用市场后实现版本查询
        return UpdateCheckResult.Failed(
            NotImplementedError("AppGalleryChecker 未启用 (packageName=$packageName)")
        )
    }
}
