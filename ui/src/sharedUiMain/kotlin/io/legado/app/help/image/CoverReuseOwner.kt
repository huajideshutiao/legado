package io.legado.app.help.image

/**
 * 读取/清理用的所有者: 落在真实请求正在用的那份有界缓存上, 只取已建立的缓存,
 * 不新建缓存、不触发预算/配置/依赖初始化。
 *
 * 缓存尚未建立时返回 null, 调用方按"无可读项"处理。
 */
internal expect fun currentCoverReuseStore(): CoverReuseStore?

/**
 * 登记用的所有者: 同一份真实缓存按真实建立路径取得, 保证有效登记真正落进那个库;
 * 登记落点不随图片服务注册时序变化。
 *
 * 真实库不存在是程序不变量破坏 (宿主必先建立图片库), 不返回空值。
 */
internal expect fun coverReuseStoreForWrite(): CoverReuseStore
