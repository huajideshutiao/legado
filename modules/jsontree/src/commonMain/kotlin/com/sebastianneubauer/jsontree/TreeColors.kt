package com.sebastianneubauer.jsontree

import androidx.compose.ui.graphics.Color

/**
 * The color palette for the json tree.
 *
 * @param keyColor The color for json keys.
 * @param stringValueColor The color for json strings.
 * @param numberValueColor The color for json numbers.
 * @param booleanValueColor The color for json booleans.
 * @param nullValueColor The color for json nulls.
 * @param indexColor The color for indices in arrays.
 * @param symbolColor The color for all symbols like brackets, colons and commas.
 * @param iconColor The color for the icon in front of collapsable items.
 */
public data class TreeColors(
    val keyColor: Color,
    val stringValueColor: Color,
    val numberValueColor: Color,
    val booleanValueColor: Color,
    val nullValueColor: Color,
    val indexColor: Color,
    val symbolColor: Color,
    val iconColor: Color,
)

/**
 * The default light palette for the json tree.
 */
public val defaultLightColors: TreeColors = TreeColors(
    keyColor = Color(0xFF1F9E8F),
    stringValueColor = Color(0xFFE9613F),
    numberValueColor = Color(0xFFF7964A),
    booleanValueColor = Color(0xFFE9BB4D),
    nullValueColor = Color(0xFFE9BB4D),
    indexColor = Color(0x991D4555),
    symbolColor = Color(0xFF1D4555),
    iconColor = Color(0xFF1D4555),
)

/**
 * The default dark palette for the json tree.
 */
public val defaultDarkColors: TreeColors = TreeColors(
    keyColor = Color(0xFF73c8a9),
    stringValueColor = Color(0xFFbd5532),
    numberValueColor = Color(0xFFe1b866),
    booleanValueColor = Color(0xFFdee1b6),
    nullValueColor = Color(0xFFdee1b6),
    indexColor = Color(0xE6798199),
    symbolColor = Color(0xFF798199),
    iconColor = Color(0xFF798199),
)
