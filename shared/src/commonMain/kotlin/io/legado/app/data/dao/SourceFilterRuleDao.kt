package io.legado.app.data.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import io.legado.app.data.entities.SourceFilterRule
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceFilterRuleDao {

    @Query("SELECT * FROM source_filter_rules ORDER BY sortOrder, createTime")
    suspend fun all(): List<SourceFilterRule>

    @Query("SELECT * FROM source_filter_rules WHERE enabled = 1 ORDER BY sortOrder, createTime")
    suspend fun enabled(): List<SourceFilterRule>

    @Query("SELECT * FROM source_filter_rules ORDER BY sortOrder, createTime")
    fun flowAll(): Flow<List<SourceFilterRule>>

    @Query("SELECT * FROM source_filter_rules WHERE name LIKE :key OR pattern LIKE :key ORDER BY sortOrder, createTime")
    fun flowSearch(key: String): Flow<List<SourceFilterRule>>

    @Query("SELECT IFNULL(MIN(sortOrder), 0) FROM source_filter_rules")
    suspend fun minOrder(): Int

    @Query("SELECT IFNULL(MAX(sortOrder), 0) FROM source_filter_rules")
    suspend fun maxOrder(): Int

    @Query("SELECT * FROM source_filter_rules WHERE id = :id")
    suspend fun get(id: String): SourceFilterRule?

    @Query("SELECT * FROM source_filter_rules WHERE id = :id")
    suspend fun findById(id: String): SourceFilterRule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg rules: SourceFilterRule)

    @Update
    suspend fun update(vararg rules: SourceFilterRule)

    @Delete
    suspend fun delete(vararg rules: SourceFilterRule)

    @Query("DELETE FROM source_filter_rules")
    suspend fun deleteAll()
}
