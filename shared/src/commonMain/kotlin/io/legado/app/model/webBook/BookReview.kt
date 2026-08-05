package io.legado.app.model.webBook

import io.legado.app.constant.AppConst
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Review
import io.legado.app.data.entities.ReviewPage
import io.legado.app.data.entities.rule.ReviewRule
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.source.SourceDebugLoggers
import io.legado.app.model.analyzeRule.AnalyzeRuleFactories
import io.legado.app.utils.decodeAnyMapOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 段评解析
 *
 * W3-e: 从 app 下沉到 shared jvmAndAndroidMain, 现下沉到 commonMain。
 * - bookSource 参数类型直接用 shared commonMain 的 BookSource 实体 (webBook 编排层与实体同模块)
 * - Debug.log(key, msg, state) → SourceDebugLoggers.impl?.log(key, msg, state)
 * - AnalyzeRule → AnalyzeRuleFactories.create (各端注册工厂返回平台子类补全 JsExtensions 面, 未注册端裸 AnalyzeRuleCore)
 * - WebBook.parseBoolean → WebBookRuleUtils.parseBoolean (解除对 WebBook object 的直接依赖)
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
        SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "≡获取段评成功:${redirectUrl}")
        SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, body, state = 50)
        val list = arrayListOf<Review>()
        val analyzeRule = AnalyzeRuleFactories.create(book, bookSource)
        analyzeRule.setContent(body)
        analyzeRule.setBaseUrl(baseUrl)
        analyzeRule.setRedirectUrl(redirectUrl)
        analyzeRule.coroutineContext = currentCoroutineContext()
        analyzeRule.variables = variables
        bookChapter?.let { analyzeRule.chapter = it }
        val listRule = reviewRule.reviewList
        if (listRule.isNullOrBlank()) {
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "≡未配置段评列表规则,视为空")
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "◇段评条数:0")
            return ReviewPage(list, hasNextPage = false)
        }
        // 是否有下一页：请求级判断，先于 item 循环求值
        // 此时 analyzeRule.content 仍是整页 body，跟普通字段规则的上下文一致
        val hasMoreRuleStr = reviewRule.hasMoreRule
        val hasNextPage = if (hasMoreRuleStr.isNullOrBlank()) {
            true // 未配置：暂当还有下一页，下面列表空时再覆盖为 false
        } else {
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌判断是否有下一页")
            val raw = analyzeRule.getString(hasMoreRuleStr)
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└$raw")
            WebBookRuleUtils.parseBoolean(raw)
        }
        // 段评总数：请求级，与 hasMoreRule 同上下文（content 仍是整页 body）
        // 透传字符串，UI 直接展示；未配置规则返回 null
        val totalCountRuleStr = reviewRule.totalCountRule
        val totalCount = if (totalCountRuleStr.isNullOrBlank()) {
            null
        } else {
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌获取段评总数")
            val raw = analyzeRule.getString(totalCountRuleStr)
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└$raw")
            raw.takeIf { it.isNotBlank() }
        }
        SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌获取段评列表")
        val elements = analyzeRule.getElements(listRule)
        if (elements.isEmpty()) {
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└列表为空")
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "◇段评条数:0")
            val effectiveHasNext =
                if (hasMoreRuleStr.isNullOrBlank()) false else hasNextPage
            return ReviewPage(list, hasNextPage = effectiveHasNext, totalCount = totalCount)
        }
        SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└列表大小:${elements.size}")
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

            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌获取段评内容", log)
            val content = analyzeRule.getString(contentRule)
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└$content", log)
            if (content.isBlank()) continue

            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌获取用户名", log)
            val name = analyzeRule.getString(nameRule).takeIf { it.isNotBlank() }
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└${name.orEmpty()}", log)

            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌获取头像", log)
            val avatarStr = analyzeRule.getString(avatarRule)
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└$avatarStr", log)
            val avatar = avatarStr.takeIf { it.isNotBlank() }

            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌获取发布时间", log)
            val postTime = analyzeRule.getString(postTimeRule).takeIf { it.isNotBlank() }
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└${postTime.orEmpty()}", log)

            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌获取附加信息", log)
            val extra = analyzeRule.getString(extraRule).takeIf { it.isNotBlank() }
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└${extra.orEmpty()}", log)

            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌获取图片列表", log)
            val images = analyzeRule.getStringList(imagesRule, isUrl = true).orEmpty()
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└${images.joinToString()}", log)

            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌获取点赞数", log)
            val voteUpStr = analyzeRule.getString(voteUpCountRule)
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└$voteUpStr", log)
            val voteUpCount = voteUpStr.toIntOrNull() ?: 0

            // 已点赞/已点踩：规则未配置则视为 false，结果按 Boolean 解析（沿用 WebBookRuleUtils.parseBoolean）
            val voted = if (reviewRule.voteUpSelectedRule.isNullOrBlank()) false else {
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌判断是否已点赞", log)
                val raw = analyzeRule.getString(voteUpSelectedRule)
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└$raw", log)
                WebBookRuleUtils.parseBoolean(raw)
            }
            val votedDown = if (reviewRule.voteDownSelectedRule.isNullOrBlank()) false else {
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌判断是否已点踩", log)
                val raw = analyzeRule.getString(voteDownSelectedRule)
                SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└$raw", log)
                WebBookRuleUtils.parseBoolean(raw)
            }

            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌获取回复数", log)
            val replyCountStr = analyzeRule.getString(replyCountRule)
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└$replyCountStr", log)
            val replyCount = replyCountStr.toIntOrNull() ?: 0

            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "┌获取段评ID", log)
            val idStr = analyzeRule.getString(idRule)
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "└$idStr", log)
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
        SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "◇段评条数:${list.size}")
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
                        // GSON.fromJson<Map<String, Any>>(bodyStr, type) → decodeAnyMapOrNull(bodyStr)
                        // AnyMapSerializer 复刻原 MapDeserializerDoubleAsIntFix 数字策略 (整数 Long/小数 Double)
                        val map = decodeAnyMapOrNull(bodyStr)
                        if (map == null) {
                            // decodeAnyMapOrNull 吞异常返回 null, 补回原 GSON 抛异常时的 onFailure 日志
                            SourceDebugLoggers.impl?.log(
                                bookSource.bookSourceUrl,
                                "段评数解析失败:${bodyStr.take(200)}"
                            )
                        }
                        map?.forEach { (k, v) ->
                            val idx = k.toIntOrNull() ?: return@forEach
                            val count = v.toString().toIntOrNull() ?: 0
                            if (count > 0) result[idx] = count
                        }
                    }
                }
            }
            result
        }.onFailure {
            SourceDebugLoggers.impl?.log(bookSource.bookSourceUrl, "段评数解析失败:${it.message}")
        }.getOrDefault(emptyMap())
    }

}
