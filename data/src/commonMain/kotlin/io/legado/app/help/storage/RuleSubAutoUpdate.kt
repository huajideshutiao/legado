package io.legado.app.help.storage

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.RuleSub
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.text
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 规则订阅的每日自动更新 (全平台唯一实现)。
 *
 * # 触发时机
 * 与每天备份同一批时机 (退出阅读页 / 退出程序 / 退到后台), 但**用各自的节流**:
 * 本机制读 [RuleSub.update] 当"上次自动更新时间", 备份读 `LocalConfigKeys.lastBackup`,
 * 两者互不影响 —— 共用同一个时间戳会让规则订阅把备份时间戳推进, 导致当天备份被跳过。
 *
 * # 导入口径
 * 解析与"哪些条目需要导入"的判据全部来自 [RuleSubAutoUpdateHook] 的实现, 与手动打开订阅是
 * 同一条链 (见该接口的 KDoc); 本件只负责挑出到期订阅、下载、回调实现、推进时间戳与记日志。
 *
 * # 时间戳口径
 * 时间戳在**发起本轮尝试之前**推进 (与自动备份在开始时就写 `lastBackup` 同一口径):
 * 于是每条订阅每天只尝试一次, 失败也等次日再试, 永久失效的地址不会在每次时机都空转一次
 * 网络超时。中断落在"时间戳已推进、尚未导入"之间时, 次日按"内容有变化才导入"的判据重跑,
 * 不产生脏数据。
 */
object RuleSubAutoUpdate {

    private const val TAG = "RuleSubAutoUpdate"

    /** 距上次更新满一天的毫秒数 (与 [BackupShared.shouldAutoBackup] 同一间隔口径)。 */
    private const val ONE_DAY_MILLIS = 86_400_000L

    /** 互斥: 多个时机入口 (退出阅读页 / 退出程序 / 退到后台) 可能同时命中。 */
    private val mutex = Mutex()

    /** 跑一轮每日自动更新。发起即返回 (退出/启动路径用, 不被网络阻塞)。 */
    fun run() {
        Coroutine.async {
            updateDue()
        }.onError {
            AppLog.put("规则订阅自动更新失败\n${it.message}", it, tag = TAG)
        }
    }

    /**
     * 挑出到期订阅并在锁内推进它们的时间戳, 再逐条下载与导入。
     *
     * 时间戳与挑选在同一个锁内写掉: 并发进来的第二个入口看到的是已推进的时间戳, 不会重复
     * 导入同一批订阅。下载与入库都在锁外 —— 一条地址失效的订阅会占满一次网络超时, 包在锁里
     * 会让其它入口长时间排队。
     */
    private suspend fun updateDue() {
        val hook = RuleSubAutoUpdateHooks.get()
        if (hook == null) {
            AppLog.put("规则订阅自动更新跳过: 未注册导入实现", tag = TAG)
            return
        }
        val due = mutex.withLock {
            val subs = readSubs() ?: return
            val now = systemCurrentTimeMillis()
            val pending = subs.filter { it.autoUpdate && now - it.update >= ONE_DAY_MILLIS }
            if (pending.isNotEmpty()) {
                pending.forEach { it.update = now }
                AppDbProviders.get().ruleSubDao.update(*pending.toTypedArray())
            }
            pending
        }
        due.forEach { updateOne(hook, it) }
    }

    /** 读取订阅列表; 取消原样上传, 其余失败记日志并放弃本轮 (返回 null)。 */
    private suspend fun readSubs(): List<RuleSub>? = try {
        AppDbProviders.get().ruleSubDao.all()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppLog.put("规则订阅自动更新读取列表失败\n${e.message}", e, tag = TAG)
        null
    }

    /** 更新单条订阅: 下载 → 交给 hook 解析入库。失败只记日志, 时间戳已在挑选时推进。 */
    private suspend fun updateOne(hook: RuleSubAutoUpdateHook, ruleSub: RuleSub) {
        try {
            val payload = download(ruleSub.url).trim()
            val text = if (payload.isAbsUrl()) download(payload).trim() else payload
            val imported = hook.importByType(ruleSub.type, text)
            if (imported <= 0) {
                AppLog.put("规则订阅自动更新: ${ruleSub.name} 无新增或需更新项", tag = TAG)
            } else {
                AppLog.put("规则订阅自动更新: ${ruleSub.name} 导入 $imported 项", tag = TAG)
            }
        } catch (e: CancellationException) {
            // 取消必须原样上传: 它表示本轮作废, 不能当成失败记日志 (分层取消语义)
            throw e
        } catch (e: Throwable) {
            AppLog.put("规则订阅自动更新失败: ${ruleSub.name}\n${e.message}", e, tag = TAG)
        }
    }

    /** 下载订阅地址, 与导入链同一口径 (`#requestWithoutUA` 结尾时去掉该后缀并不带 UA)。 */
    private suspend fun download(url: String): String =
        OkHttpClientProviders.get().okHttpClient.newCallResponseBody {
            if (url.endsWith("#requestWithoutUA")) {
                url(url.substringBeforeLast("#requestWithoutUA"))
                header(AppConst.UA_NAME, "null")
            } else {
                url(url)
            }
        }.decompressed().text()
}
