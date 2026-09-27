package com.sebastianneubauer.jsontree

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonElement

@Immutable
internal sealed interface JsonTreeParserState {
    data object Loading : JsonTreeParserState
    data class Ready(val list: List<JsonTreeElement>) : JsonTreeParserState

    sealed interface Parsing : JsonTreeParserState {
        data class Parsed(val jsonElement: JsonElement) : Parsing
        data class Error(val throwable: Throwable) : Parsing
    }
}
