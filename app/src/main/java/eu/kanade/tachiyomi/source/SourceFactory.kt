// Copyright The Keiyoushi Contributors. Apache-2.0.
package eu.kanade.tachiyomi.source

interface SourceFactory {

    fun createSources(): List<Source>
}
