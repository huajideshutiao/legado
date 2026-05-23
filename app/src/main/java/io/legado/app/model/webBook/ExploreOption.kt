package io.legado.app.model.webBook

data class ExploreOption(
    val name: String,
    val options: List<Pair<String, String>>,
    var selectedValue: String
)
