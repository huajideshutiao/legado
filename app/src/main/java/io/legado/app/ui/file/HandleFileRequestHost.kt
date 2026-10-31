package io.legado.app.ui.file

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * 宿主持有的文件请求纯状态 (单一权威): 挂在宿主 Activity 的 ViewModelStore 上,
 * 配置重建保留同一实例, 界面真正退出随 ViewModelStore 清空。
 *
 * 只持纯请求数据: 参数、消费资格与业务终态、真实SDK在途事实、暂存回执与等待方回执。
 * 不持 Activity/入口/弹窗引用; UI 执行体 ([HandleFileSession]) 按请求记录认领与交付,
 * 并在各自宿主作用域收集资格状态收口自己的UI —— 权威与界面之间只有 StateFlow 一条通道。
 * 资格经 [RequestRecord.terminate] 在撤销线程即时终结, 之后任何执行体都读不到可消费。
 */
class HandleFileRequestHost : ViewModel() {

    /** 一条进行中的文件请求 (纯数据): 业务资格/终态与真实SDK占用都在这份记录上。 */
    inner class RequestRecord(
        val requestId: Long,
        /** 重建后仍有效的入口用稳定 key 重绑; 页面级入口为 null (重建即作废)。 */
        val key: String?,
        val param: HandleFileContract.HandleFileParam,
        /** 发起时绑定书籍的地址 (重建后按它取回同一本书的快照)。 */
        val bookUrl: String?,
    ) {

        /** 消费资格与业务终态: 撤销/交付/真实失败同归于此, 终结后不可再消费。 */
        private val consumableState = MutableStateFlow(true)

        /** 可收集的资格状态: 会话在宿主作用域收集, 终结即关闭自己的UI。 */
        val consumable: StateFlow<Boolean> get() = consumableState

        /** 同步读: 终结的瞬间起, 任何线程都读不到可消费。 */
        val isConsumable: Boolean get() = consumableState.value

        /** 真实SDK在途事实: 只由系统回执/启动失败终结, UI收口与撤销不经此。 */
        @Volatile
        var awaitingSys = false

        @Volatile
        var awaitingChannel: String? = null

        /** 暂存回执 (真实回执先于有效入口绑定到达时); 交付/回收时清除。主线程读写。 */
        var pendingResult: HandleFileContract.Result? = null

        /**
         * 终结消费资格 (撤销/交付同归): 真实SDK仍在途时记录保持到真实回执,
         * 否则当场回收 —— 已终结的记录不等待不存在的消费者。
         */
        fun terminate() {
            consumableState.value = false
            if (!awaitingSys) removeSelf()
        }

        /** 真实SDK回执已到: 先终结真实在途事实; 资格已终结的记录随之回收。 */
        fun receiptSettled() {
            awaitingSys = false
            awaitingChannel = null
            if (!isConsumable) removeSelf()
        }

        private fun removeSelf() {
            this@HandleFileRequestHost.removeRequest(requestId)
        }
    }

    /** 一次"书籍保存位置"选择的登记 (纯数据): 每次选择一条, 终态即移除。 */
    inner class PickRecord(
        val pickId: Long,
        /** 本窗口会话内认领请求用的稳定 key。 */
        val key: String,
        /** 等待方回执 (重建重绑后仍交给它); 随记录存亡, 不持界面引用。 */
        val onSelected: (String?, Throwable?) -> Unit,
    ) {

        /** 父Job撤销订阅的解除句柄: 请求终态后释放, 不让长寿父Job保着已终结的登记。 */
        var cancelHandle: DisposableHandle? = null
    }

    private val lock = Any()
    private val requests = LinkedHashMap<Long, RequestRecord>()
    private val picks = LinkedHashMap<Long, PickRecord>()
    private val requestSeq = AtomicLong()
    private val pickSeq = AtomicLong()

    fun nextRequestId(): Long = requestSeq.incrementAndGet()

    fun addRequest(record: RequestRecord) = synchronized(lock) { requests[record.requestId] = record }

    fun removeRequest(requestId: Long) {
        synchronized(lock) { requests.remove(requestId) }
    }

    fun requestRecords(): List<RequestRecord> = synchronized(lock) { requests.values.toList() }

    fun requestForKey(key: String): RequestRecord? =
        synchronized(lock) { requests.values.firstOrNull { it.key == key } }

    /**
     * 撤一次"书籍保存位置"选择: 登记移除, 名下请求资格即时终结 —— 尚未launch的登记与
     * 已launch的请求面对同一份已建立的资格, 取消不因记录尚未挂到UI而落空。
     */
    fun revokePick(pickId: Long, key: String) {
        synchronized(lock) { picks.remove(pickId) }
        val records = synchronized(lock) { requests.values.filter { it.key == key } }
        records.forEach { it.terminate() }
    }

    fun nextPickId(): Long = pickSeq.incrementAndGet()

    fun addPick(record: PickRecord) = synchronized(lock) { picks[record.pickId] = record }

    fun removePick(pickId: Long) {
        synchronized(lock) { picks.remove(pickId) }
    }

    fun livePicks(): List<PickRecord> = synchronized(lock) { picks.values.toList() }

    override fun onCleared() {
        synchronized(lock) {
            requests.clear()
            picks.clear()
        }
    }
}
