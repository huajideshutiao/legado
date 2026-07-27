package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.time.Clock
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "ruleSubs")
data class RuleSub(
    @PrimaryKey
    val id: Long = Clock.System.now().toEpochMilliseconds(),
    var name: String = "",
    var url: String = "",
    var type: Int = 0,
    var customOrder: Int = 0,
    var autoUpdate: Boolean = false,
    var update: Long = Clock.System.now().toEpochMilliseconds()
)
