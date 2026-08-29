package io.legado.app.data.entities

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "caches", withoutRowId = true)
data class Cache(
    @PrimaryKey
    val key: String = "",
    var value: String? = null,
    var deadline: Long = 0L
)