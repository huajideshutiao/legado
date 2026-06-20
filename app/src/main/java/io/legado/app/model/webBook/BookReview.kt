package io.legado.app.model.webBook

import com.google.gson.reflect.TypeToken
import io.legado.app.constant.AppConst
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Review
import io.legado.app.data.entities.ReviewPage
import io.legado.app.data.entities.rule.ReviewRule
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.Debug
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.utils.GSON
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.mozilla.javascript.NativeObject

/**
 * 段评解析
 */
object BookReview {

    /**
     * 解析段评列表
     */
    suspend fun analyzeReviewList(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter?,
        baseUrl: String,
        redirectUrl: String,
        body: String?,
        reviewRule: ReviewRule,
        variables: Map<AppConst.JsVarName, Any>? = null
    ): ReviewPage {
        body ?: throw NoStackTraceException("段评内容为空")
        Debug.log(bookSource.bookSourceUrl, "≡获取段评成功:${redirectUrl}")
        Debug.log(bookSource.bookSourceUrl, body, state = 50)
        val list = arrayListOf<Review>()
        val analyzeRule = AnalyzeRule(book, bookSource)
        analyzeRule.setContent(body)
        analyzeRule.setBaseUrl(baseUrl)
        analyzeRule.setRedirectUrl(redirectUrl)
        analyzeRule.coroutineContext = currentCoroutineContext()
        analyzeRule.variables = variables
        bookChapter?.let { analyzeRule.chapter = it }
        val listRule = reviewRule.reviewList
        if (listRule.isNullOrBlank()) {
            Debug.log(bookSource.bookSourceUrl, "≡未配置段评列表规则,视为空")
            Debug.log(bookSource.bookSourceUrl, "◇段评条数:0")
            return ReviewPage(list, hasNextPage = false)
        }
        // 是否有下一页：请求级判断，先于 item 循环求值
        // 此时 analyzeRule.content 仍是整页 body，跟普通字段规则的上下文一致
        val hasMoreRuleStr = reviewRule.hasMoreRule
        val hasNextPage = if (hasMoreRuleStr.isNullOrBlank()) {
            true // 未配置：暂当还有下一页，下面列表空时再覆盖为 false
        } else {
            Debug.log(bookSource.bookSourceUrl, "┌判断是否有下一页")
            val raw = analyzeRule.getString(hasMoreRuleStr)
            Debug.log(bookSource.bookSourceUrl, "└$raw")
            WebBook.parseBoolean(raw)
        }
        // 段评总数：请求级，与 hasMoreRule 同上下文（content 仍是整页 body）
        // 透传字符串，UI 直接展示；未配置规则返回 null
        val totalCountRuleStr = reviewRule.totalCountRule
        val totalCount = if (totalCountRuleStr.isNullOrBlank()) {
            null
        } else {
            Debug.log(bookSource.bookSourceUrl, "┌获取段评总数")
            val raw = analyzeRule.getString(totalCountRuleStr)
            Debug.log(bookSource.bookSourceUrl, "└$raw")
            raw.takeIf { it.isNotBlank() }
        }
        Debug.log(bookSource.bookSourceUrl, "┌获取段评列表")
        val elements = analyzeRule.getElements(listRule)
        if (elements.isEmpty()) {
            Debug.log(bookSource.bookSourceUrl, "└列表为空")
            Debug.log(bookSource.bookSourceUrl, "◇段评条数:0")
            val effectiveHasNext =
                if (hasMoreRuleStr.isNullOrBlank()) false else hasNextPage
            return ReviewPage(list, hasNextPage = effectiveHasNext, totalCount = totalCount)
        }
        Debug.log(bookSource.bookSourceUrl, "└列表大小:${elements.size}")
        val avatarRule = analyzeRule.splitSourceRule(reviewRule.avatarRule)
        val nameRule = analyzeRule.splitSourceRule(reviewRule.nameRule)
        val contentRule = analyzeRule.splitSourceRule(reviewRule.contentRule)
        val postTimeRule = analyzeRule.splitSourceRule(reviewRule.postTimeRule)
        val extraRule = analyzeRule.splitSourceRule(reviewRule.extraRule)
        val imagesRule = analyzeRule.splitSourceRule(reviewRule.imagesRule)
        val voteUpCountRule = analyzeRule.splitSourceRule(reviewRule.voteUpCountRule)
        val voteUpSelectedRule = analyzeRule.splitSourceRule(reviewRule.voteUpSelectedRule)
        val voteDownSelectedRule = analyzeRule.splitSourceRule(reviewRule.voteDownSelectedRule)
        val replyCountRule = analyzeRule.splitSourceRule(reviewRule.replyCountRule)
        val idRule = analyzeRule.splitSourceRule(reviewRule.reviewIdRule)
        for ((index, item) in elements.withIndex()) {
            currentCoroutineContext().ensureActive()
            analyzeRule.setContent(item)
            val log = index == 0

            Debug.log(bookSource.bookSourceUrl, "┌获取段评内容", log)
            val content = analyzeRule.getString(contentRule)
            Debug.log(bookSource.bookSourceUrl, "└$content", log)
            if (content.isBlank()) continue

            Debug.log(bookSource.bookSourceUrl, "┌获取用户名", log)
            val name = analyzeRule.getString(nameRule).takeIf { it.isNotBlank() }
            Debug.log(bookSource.bookSourceUrl, "└${name.orEmpty()}", log)

            Debug.log(bookSource.bookSourceUrl, "┌获取头像", log)
            val avatarStr = analyzeRule.getString(avatarRule)
            Debug.log(bookSource.bookSourceUrl, "└$avatarStr", log)
            val avatar = avatarStr.takeIf { it.isNotBlank() }

            Debug.log(bookSource.bookSourceUrl, "┌获取发布时间", log)
            val postTime = analyzeRule.getString(postTimeRule).takeIf { it.isNotBlank() }
            Debug.log(bookSource.bookSourceUrl, "└${postTime.orEmpty()}", log)

            Debug.log(bookSource.bookSourceUrl, "┌获取附加信息", log)
            val extra = analyzeRule.getString(extraRule).takeIf { it.isNotBlank() }
            Debug.log(bookSource.bookSourceUrl, "└${extra.orEmpty()}", log)

            Debug.log(bookSource.bookSourceUrl, "┌获取图片列表", log)
            val images = analyzeRule.getStringList(imagesRule, isUrl = true).orEmpty()
            Debug.log(bookSource.bookSourceUrl, "└${images.joinToString()}", log)

            Debug.log(bookSource.bookSourceUrl, "┌获取点赞数", log)
            val voteUpStr = analyzeRule.getString(voteUpCountRule)
            Debug.log(bookSource.bookSourceUrl, "└$voteUpStr", log)
            val voteUpCount = voteUpStr.toIntOrNull() ?: 0

            // 已点赞/已点踩：规则未配置则视为 false，结果按 Boolean 解析（沿用 WebBook.parseBoolean）
            val voted = if (reviewRule.voteUpSelectedRule.isNullOrBlank()) false else {
                Debug.log(bookSource.bookSourceUrl, "┌判断是否已点赞", log)
                val raw = analyzeRule.getString(voteUpSelectedRule)
                Debug.log(bookSource.bookSourceUrl, "└$raw", log)
                WebBook.parseBoolean(raw)
            }
            val votedDown = if (reviewRule.voteDownSelectedRule.isNullOrBlank()) false else {
                Debug.log(bookSource.bookSourceUrl, "┌判断是否已点踩", log)
                val raw = analyzeRule.getString(voteDownSelectedRule)
                Debug.log(bookSource.bookSourceUrl, "└$raw", log)
                WebBook.parseBoolean(raw)
            }

            Debug.log(bookSource.bookSourceUrl, "┌获取回复数", log)
            val replyCountStr = analyzeRule.getString(replyCountRule)
            Debug.log(bookSource.bookSourceUrl, "└$replyCountStr", log)
            val replyCount = replyCountStr.toIntOrNull() ?: 0

            Debug.log(bookSource.bookSourceUrl, "┌获取段评ID", log)
            val idStr = analyzeRule.getString(idRule)
            Debug.log(bookSource.bookSourceUrl, "└$idStr", log)
            val id = idStr.takeIf { it.isNotBlank() }

            list.add(
                Review(
                    id = id,
                    avatar = avatar,
                    name = name,
                    content = content,
                    postTime = postTime,
                    extra = extra,
                    voteUpCount = voteUpCount,
                    replyCount = replyCount,
                    images = images,
                    voted = voted,
                    votedDown = votedDown
                )
            )
        }
        Debug.log(bookSource.bookSourceUrl, "◇段评条数:${list.size}")
        return ReviewPage(list, hasNextPage = hasNextPage, totalCount = totalCount)
    }

    /**
     * 解析整章段评数 map：{paragraphIndex: count}，0=章节级评论
     * 约定书源返回 JS 对象或 JSON 字符串。
     */
    fun analyzeReviewCount(
        bookSource: BookSource,
        body: Any?,
    ): Map<Int, Int> {
        if (body == null) return emptyMap()
        return runCatching {
            val result = HashMap<Int, Int>()
            when (body) {
                is NativeObject -> {
                    body.ids.forEach { key ->
                        val idx = key.toString().toIntOrNull() ?: return@forEach
                        val count = body[key].toString().toIntOrNull() ?: 0
                        if (count > 0) result[idx] = count
                    }
                }

                is Map<*, *> -> {
                    body.forEach { (k, v) ->
                        val idx = k.toString().toIntOrNull() ?: return@forEach
                        val count = v?.toString()?.toIntOrNull() ?: 0
                        if (count > 0) result[idx] = count
                    }
                }

                else -> {
                    val bodyStr = body.toString().trim()
                    if (bodyStr.isNotEmpty() && bodyStr != "[object Object]") {
                        val map = GSON.fromJson<Map<String, Any>>(
                            bodyStr,
                            object : TypeToken<Map<String, Any>>() {}.type
                        )
                        map.forEach { (k, v) ->
                            val idx = k.toIntOrNull() ?: return@forEach
                            val count = v.toString().toIntOrNull() ?: 0
                            if (count > 0) result[idx] = count
                        }
                    }
                }
            }
            result
        }.onFailure {
            Debug.log(bookSource.bookSourceUrl, "段评数解析失败:${it.localizedMessage}")
        }.getOrDefault(emptyMap())
    }

}
