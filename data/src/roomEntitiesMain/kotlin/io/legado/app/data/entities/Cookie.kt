package io.legado.app.data.entities

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "cookies", withoutRowId = true)
data class Cookie(
    @PrimaryKey
    var url: String = "",
    var cookie: String = ""
)