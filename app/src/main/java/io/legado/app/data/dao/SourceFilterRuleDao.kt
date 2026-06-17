package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.SourceFilterRule
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceFilterRuleDao {

    @get:Query("SELECT * FROM source_filter_rules ORDER BY sortOrder, createTime")
    val all: List<SourceFilterRule>

    @get:Query("SELECT * FROM source_filter_rules WHERE enabled = 1 ORDER BY sortOrder, createTime")
    val enabled: List<SourceFilterRule>

    @Query("SELECT * FROM source_filter_rules ORDER BY sortOrder, createTime")
    fun flowAll(): Flow<List<SourceFilterRule>>

    @Query("SELECT * FROM source_filter_rules WHERE name LIKE :key OR pattern LIKE :key ORDER BY sortOrder, createTime")
    fun flowSearch(key: String): Flow<List<SourceFilterRule>>

    @get:Query("SELECT IFNULL(MIN(sortOrder), 0) FROM source_filter_rules")
    val minOrder: Int

    @get:Query("SELECT IFNULL(MAX(sortOrder), 0) FROM source_filter_rules")
    val maxOrder: Int

    @Query("SELECT * FROM source_filter_rules WHERE id = :id")
    fun get(id: String): SourceFilterRule?

    @Query("SELECT * FROM source_filter_rules WHERE id = :id")
    fun findById(id: String): SourceFilterRule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg rules: SourceFilterRule)

    @Update
    fun update(vararg rules: SourceFilterRule)

    @Delete
    fun delete(vararg rules: SourceFilterRule)

    @Query("DELETE FROM source_filter_rules")
    fun deleteAll()
}
