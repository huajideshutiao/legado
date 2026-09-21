package io.legado.app.ui.book.read

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.legado.app.data.entities.Review

/**
 * 段评点赞/点踩/展开状态助手 (KMP 版, commonMain)。
 *
 * # 背景
 *
 * 对照 app 端 `ReviewListDialog` 内 `seedVoteFromItem / toggleVoteUp / toggleVoteDown /
 * revertVoteUp / revertVoteDown` 与 desktop 端 `ReviewListScreen` 内同名私有函数:
 * 两端逻辑完全一致 (互斥语义: 点赞时移除点踩, 反之亦然), 属于重复实现, 下沉到 shared 单一数据源。
 *
 * # 状态语义 (严格对齐 app 端)
 *
 * - [votedIds] / [votedDownIds]: 当前用户已点赞 / 已点踩的段评 id 集合, 互斥 (同一 id 不会同时存在)
 * - [expandedKeys]: 段评展开/收起 key 集合 (长评论内容折叠/展开)
 * - [voteSeeded]: 已用书源返回的 voted/votedDown 初始态灌入过本地集合的 id; 同一 id 只灌一次,
 *   防止用户翻转后被 item 旧值回灌覆盖
 *
 * # seedVote 抹除"自己那 1 票"的说明
 *
 * 书源解析的 voteUpCount 一般是页面上的总数 (已含当前用户的点赞),
 * 显示按 `voteUpCount + (isVoted?1:0)` 计算, 所以 [seedVote] 把 item.voteUpCount 抹去"自己那 1 票",
 * 让基数永远是"不含自己" (与 app 端 `if (item.voteUpCount > 0) item.voteUpCount -= 1` 一致)。
 */
class ReviewVoteHelper {

    private val _votedIds = MutableStateFlow<Set<String>>(emptySet())
    val votedIds: StateFlow<Set<String>> = _votedIds.asStateFlow()

    private val _votedDownIds = MutableStateFlow<Set<String>>(emptySet())
    val votedDownIds: StateFlow<Set<String>> = _votedDownIds.asStateFlow()

    private val _expandedKeys = MutableStateFlow<Set<String>>(emptySet())
    val expandedKeys: StateFlow<Set<String>> = _expandedKeys.asStateFlow()

    /** 已灌入初始态的 id 集合 (防回灌), 与 app 端 `voteSeeded` 字段对应。 */
    val voteSeeded: HashSet<String> = HashSet()

    /**
     * 把书源返回的 voted/votedDown 初始态首次灌入本地集合 (对照 app 端 `seedVoteFromItem`)。
     *
     * 同一 id 只灌一次 ([voteSeeded] 跟踪); 重复调用直接返回。
     * 副作用: 当 item.voted 且 voteUpCount>0 时, 抹去"自己那 1 票" (item.voteUpCount -= 1)。
     */
    fun seedVote(item: Review) {
        val id = item.id ?: return
        if (!voteSeeded.add(id)) return
        if (item.voted) {
            _votedIds.value = _votedIds.value + id
            if (item.voteUpCount > 0) item.voteUpCount -= 1
        }
        if (item.votedDown) {
            _votedDownIds.value = _votedDownIds.value + id
        }
    }

    /**
     * 翻转点赞态 (对照 app 端 `toggleVoteUp`)。
     *
     * 互斥: 点赞时同步移除点踩 ([votedDownIds] - id); 取消点赞时仅移除自身。
     *
     * @return 翻转后的最新态 (true=已点赞, false=未点赞)
     */
    fun toggleVoteUp(id: String): Boolean {
        val target = !_votedIds.value.contains(id)
        if (target) {
            _votedIds.value = _votedIds.value + id
            _votedDownIds.value = _votedDownIds.value - id // 互斥: 点赞时移除点踩
        } else {
            _votedIds.value = _votedIds.value - id
        }
        return target
    }

    /**
     * 翻转点踩态 (对照 app 端 `toggleVoteDown`)。
     *
     * 互斥: 点踩时同步移除点赞 ([votedIds] - id); 取消点踩时仅移除自身。
     *
     * @return 翻转后的最新态 (true=已点踩, false=未点踩)
     */
    fun toggleVoteDown(id: String): Boolean {
        val target = !_votedDownIds.value.contains(id)
        if (target) {
            _votedDownIds.value = _votedDownIds.value + id
            _votedIds.value = _votedIds.value - id // 互斥: 点踩时移除点赞
        } else {
            _votedDownIds.value = _votedDownIds.value - id
        }
        return target
    }

    /**
     * 回滚点赞翻转 (对照 app 端 `revertVoteUp`)。
     *
     * 规则执行失败时调用, 把 [toggleVoteUp] 的乐观翻转撤销: 原本加的移除, 原本移除的加回。
     */
    fun revertVoteUp(id: String) {
        _votedIds.value = if (_votedIds.value.contains(id)) _votedIds.value - id else _votedIds.value + id
    }

    /**
     * 回滚点踩翻转 (对照 app 端 `revertVoteDown`)。
     *
     * 规则执行失败时调用, 把 [toggleVoteDown] 的乐观翻转撤销。
     */
    fun revertVoteDown(id: String) {
        _votedDownIds.value =
            if (_votedDownIds.value.contains(id)) _votedDownIds.value - id else _votedDownIds.value + id
    }

    /**
     * 翻转段评展开/收起态 (对照 desktop 端 `onToggleExpand`)。
     */
    fun toggleExpand(key: String) {
        _expandedKeys.value =
            if (_expandedKeys.value.contains(key)) _expandedKeys.value - key else _expandedKeys.value + key
    }
}
