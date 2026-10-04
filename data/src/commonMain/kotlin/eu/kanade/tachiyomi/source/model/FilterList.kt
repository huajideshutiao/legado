// Copyright The Keiyoushi Contributors. Apache-2.0.
package eu.kanade.tachiyomi.source.model

@Suppress("Unused", "JavaDefaultMethodsNotOverriddenByDelegation")
data class FilterList(val list: List<Filter<*>>) : List<Filter<*>> by list {

    constructor(vararg fs: Filter<*>) : this(if (fs.isNotEmpty()) fs.asList() else emptyList())
}
