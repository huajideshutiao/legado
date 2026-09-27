package com.sebastianneubauer.jsontree

/**
 * The initial state for the json tree before any user interaction.
 *
 * EXPANDED - Expand all items to show the full tree.
 * COLLAPSED - Collapse all items to only show the root item.
 * FIRST_ITEM_EXPANDED - Expand the root item, but keep all other items collapsed.
 */
public enum class TreeState {
    EXPANDED,
    COLLAPSED,
    FIRST_ITEM_EXPANDED
}
