package io.legado.app.data.entities

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(tableName = "cookies", indices = [(Index(value = ["url"], unique = true))])
data class Cookie(
    @PrimaryKey
    var url: String = "",
    var cookie: String = ""
)