package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.time.Clock


@Entity(tableName = "txtTocRules")
data class TxtTocRule(
    @PrimaryKey
    var id: Long = Clock.System.now().toEpochMilliseconds(),
    var name: String = "",
    var rule: String = "",
    var example: String? = null,
    var serialNumber: Int = -1,
    var enable: Boolean = true
) {

    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (other is TxtTocRule) {
            return id == other.id
        }
        return false
    }

}