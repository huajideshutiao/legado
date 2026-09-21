package io.legado.app.lib.epublib.epub

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.w3c.dom.Text

/**
 * Utility methods for working with the DOM.
 * 
 * @author paul
 */
// package
internal object DOMUtil {
    /**
     * First tries to get the attribute value by doing an getAttributeNS on the element, if that gets an empty element it does a getAttribute without namespace.
     * 
     * @param element   element
     * @param namespace namespace
     * @param attribute attribute
     * @return String Attribute
     */
    fun getAttribute(
        element: Element, namespace: String?,
        attribute: String?
    ): String? {
        return element.getAttributeNS(namespace, attribute)
            .ifEmpty { element.getAttribute(attribute) }
    }

    /**
     * Gets all descendant elements of the given parentElement with the given namespace and tagname and returns their text child as a list of String.
     * 
     * @param parentElement parentElement
     * @param namespace     namespace
     * @param tagName       tagName
     * @return List<String>
    </String> */
    fun getElementsTextChild(
        parentElement: Element,
        namespace: String?, tagName: String?
    ): MutableList<String?> {
        val elements = parentElement
            .getElementsByTagNameNS(namespace, tagName)
        //ArrayList 初始化时指定长度提高性能
        val result: MutableList<String?> = ArrayList<String?>(elements.getLength())
        for (i in 0..<elements.getLength()) {
            result.add(getTextChildrenContent(elements.item(i) as Element?))
        }
        return result
    }

    /**
     * Finds in the current document the first element with the given namespace and elementName and with the given findAttributeName and findAttributeValue.
     * It then returns the value of the given resultAttributeName.
     * 
     * @param document            document
     * @param namespace           namespace
     * @param elementName         elementName
     * @param findAttributeName   findAttributeName
     * @param findAttributeValue  findAttributeValue
     * @param resultAttributeName resultAttributeName
     * @return String value
     */
    fun getFindAttributeValue(
        document: Document,
        namespace: String?, elementName: String?, findAttributeName: String?,
        findAttributeValue: String, resultAttributeName: String?
    ): String? {
        val metaTags = document.getElementsByTagNameNS(namespace, elementName)
        for (i in 0..<metaTags.getLength()) {
            val metaElement = metaTags.item(i) as Element
            if (findAttributeValue
                    .equals(metaElement.getAttribute(findAttributeName), ignoreCase = true)
                && !metaElement.getAttribute(resultAttributeName).isNullOrBlank()
            ) {
                return metaElement.getAttribute(resultAttributeName)
            }
        }
        return null
    }

    /**
     * Gets the first element that is a child of the parentElement and has the given namespace and tagName
     * 
     * @param parentElement parentElement
     * @param namespace     namespace
     * @param tagName       tagName
     * @return Element
     */
    fun getElementsByTagNameNS(
        parentElement: Element,
        namespace: String?, tagName: String?
    ): NodeList? {
        var nodes = parentElement.getElementsByTagNameNS(namespace, tagName)
        if (nodes.getLength() != 0) {
            return nodes
        }
        nodes = parentElement.getElementsByTagName(tagName)
        if (nodes.getLength() == 0) {
            return null
        }
        return nodes
    }

    /**
     * Gets the first element that is a child of the parentElement and has the given namespace and tagName
     * 
     * @param parentElement parentElement
     * @param namespace     namespace
     * @param tagName       tagName
     * @return Element
     */
    fun getElementsByTagNameNS(
        parentElement: Document,
        namespace: String?, tagName: String?
    ): NodeList? {
        var nodes = parentElement.getElementsByTagNameNS(namespace, tagName)
        if (nodes.getLength() != 0) {
            return nodes
        }
        nodes = parentElement.getElementsByTagName(tagName)
        if (nodes.getLength() == 0) {
            return null
        }
        return nodes
    }

    /**
     * Gets the first element that is a child of the parentElement and has the given namespace and tagName
     * 
     * @param parentElement parentElement
     * @param namespace     namespace
     * @param tagName       tagName
     * @return Element
     */
    fun getFirstElementByTagNameNS(
        parentElement: Element,
        namespace: String?, tagName: String?
    ): Element? {
        var nodes = parentElement.getElementsByTagNameNS(namespace, tagName)
        if (nodes.getLength() != 0) {
            return nodes.item(0) as Element?
        }
        nodes = parentElement.getElementsByTagName(tagName)
        if (nodes.getLength() == 0) {
            return null
        }
        return nodes.item(0) as Element?
    }

    /**
     * The contents of all Text nodes that are children of the given parentElement.
     * The result is trim()-ed.
     * 
     * 
     * The reason for this more complicated procedure instead of just returning the data of the firstChild is that
     * when the text is Chinese characters then on Android each Characater is represented in the DOM as
     * an individual Text node.
     * 
     * @param parentElement parentElement
     * @return String value
     */
    fun getTextChildrenContent(parentElement: Element?): String? {
        if (parentElement == null) {
            return null
        }
        val result = StringBuilder()
        val childNodes = parentElement.getChildNodes()
        for (i in 0..<childNodes.getLength()) {
            val node = childNodes.item(i)
            if ((node == null) ||
                (node.getNodeType() != Node.TEXT_NODE)
            ) {
                continue
            }
            result.append((node as Text).getData())
        }
        return result.toString().trim()
    }
}
