@file:Suppress("unused")

package org.jsoup.nodes

import com.fleeksoft.ksoup.nodes.FormElement as KsFormElement
import org.jsoup.select.Elements

/**
 * jsoup 兼容层 FormElement 门面,委托底层 [KsFormElement]。
 *
 * 扩展经 Elements#forms() 取得表单后按此类型使用; elements()/addElement() 与真实 jsoup 逐字对齐
 * (elements() = 当前可提交子控件 ∪ addElement 关联的控件)。submit()/formData() 底层 ksoup 无支撑, 不纳入门面。
 */
public open class FormElement internal constructor(node: KsFormElement) : Element(node) {

    private val ksoupFormElement: KsFormElement
        get() = ksoupNode as KsFormElement

    /** 对齐 jsoup FormElement#elements 语义: 返回与本表单关联的表单控件元素列表 */
    public fun elements(): Elements = asFacadeElements(ksoupFormElement.elements())

    /** 对齐 jsoup FormElement#addElement 语义: 将控件元素关联到本表单, 返回自身 */
    public fun addElement(element: Element): FormElement {
        ksoupFormElement.addElement(element.ksoupElement)
        return this
    }

    public override fun clone(): FormElement = FormElement(ksoupFormElement.clone())
}
