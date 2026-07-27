package com.github.jershell.rjpath

import kotlinx.serialization.json.JsonElement

/**
 * Base selector interface
 */
interface Selector {
    fun select(node: Node): List<Node>
}

/**
 * Node definition
 */
data class Node(val value: JsonElement, val location: Location)

/**
 * Location definition
 */
sealed class Location {
    data class Property(val name: String, val parent: Location?) : Location()
    data class Index(val index: Int, val parent: Location?) : Location()
    object Root : Location()

    override fun toString(): String {
        return when (this) {
            is Property -> {
                val parentPath = parent?.toString() ?: "$"
                if (parentPath == "$") {
                    "$.$name"
                } else {
                    "$parentPath.$name"
                }
            }
            is Index -> {
                val parentPath = parent?.toString() ?: "$"
                if (parentPath == "$") {
                    "$[$index]"
                } else {
                    "$parentPath[$index]"
                }
            }
            Root -> "$"
        }
    }

    /**
     * Returns full path to the node
     */
    fun toFullPath(): String {
        return when (this) {
            is Property -> {
                val parentPath = parent?.toFullPath() ?: "$"
                if (parentPath == "$") {
                    "$.$name"
                } else {
                    "$parentPath.$name"
                }
            }
            is Index -> {
                val parentPath = parent?.toFullPath() ?: "$"
                if (parentPath == "$") {
                    "$[$index]"
                } else {
                    "$parentPath[$index]"
                }
            }
            Root -> "$"
        }
    }
}

/**
 * Function extension interface
 */
interface FunctionExtension {
    val name: String
    fun evaluate(arguments: List<JsonElement>): JsonElement
}