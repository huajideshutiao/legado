package io.legado.desktop.help

import io.legado.app.help.StartupMaintenanceShared

/**
 * 桌面端首启 / 升级时的默认数据补齐 (对照 app 端 `App.onCreate` 的 `DefaultData.upVersion()`
 * 与建库回调预置数据)。
 *
 * # 下沉说明
 *
 * 编排本体 (预置分组补齐 / 键盘助手补齐 / `importOnce` 版本门 / httpTTS / txtTocRule / dictRule)
 * 已上移到 :data commonMain 的 [StartupMaintenanceShared.initDefaultData], 四端共用同一份实现。
 * 原先卡在桌面端无法上移的只有"当前版本号"一个平台值, 现已由
 * [io.legado.app.help.AppVersionProvider] 抽象注入, 桌面端取值源仍是
 * [DesktopAppInfo.versionCode] (MANIFEST Implementation-Version 解析, 语义与下沉前逐字相同)。
 *
 * 本文件只剩"转发"一件事, 保留原函数入口以免调用方 (`DesktopCore`) 变动 —— 版本号 provider 的注册
 * 放在桌面核心启动序列里 (`DesktopCore.registerEarlyProviders`, 与其他 provider 同步注册), 而不是
 * 在本函数首次被调时惰性注册, 后者会让"谁先读版本号"变成隐式约定。
 *
 * # 三道设计 (语义与下沉前逐字一致, 详见共享件 KDoc)
 *
 * 1. **预置书架分组补齐**: 只在版本号推进时补, 按主键缺啥补啥 (不覆盖用户改动)。
 *    主路径是建库回调 (`BundledDatabaseDriver` 构造时挂的 `AppDatabaseDefaults.onCreate`),
 *    这里只兜"建库回调挂上之前的老库"与"以后新增的预置项"。
 * 2. **键盘助手补齐**: 同门控, 按 (type,key) 逐项补缺。
 * 3. **`importOnce` 版本门**: 各资源版本 key **只在导入成功后**才写回, 导入失败不推进
 *    `appVersionCode`, 下次启动整体重试。
 *
 * 前两道判断必须在第三道之前: 第三道末尾才写回 `appVersionCode`, 反过来同一版本内就再也补不上。
 */
suspend fun initDesktopDefaultData() {
    StartupMaintenanceShared.initDefaultData()
}
