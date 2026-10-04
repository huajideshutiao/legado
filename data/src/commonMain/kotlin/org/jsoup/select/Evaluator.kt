@file:Suppress("unused")

package org.jsoup.select

import com.fleeksoft.ksoup.select.Evaluator as KsEvaluator

/**
 * jsoup 兼容层 Evaluator 门面,委托底层 [KsEvaluator]。
 *
 * 扩展源码只用到 Tag/Class/Id 三个具体选择器 (以对象形式传给 select/selectFirst),
 * 完整 CSS 选择器仍走 select(String) 内部的 QueryParser。
 */
public open class Evaluator internal constructor(internal val ksoupEvaluator: KsEvaluator)

public class Tag(tagName: String) : Evaluator(KsEvaluator.Tag(tagName))

public class Id(id: String) : Evaluator(KsEvaluator.Id(id))

public class Class(className: String) : Evaluator(KsEvaluator.Class(className))
