package io.legado.app.data.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import io.legado.app.data.entities.DictRule
import kotlinx.coroutines.flow.Flow


@Dao
interface DictRuleDao {

    @Query("select * from dictRules order by sortNumber")
    suspend fun all(): List<DictRule>

    @Query("select * from dictRules where enabled = 1 order by sortNumber")
    suspend fun enabled(): List<DictRule>

    @Query("select * from dictRules order by sortNumber")
    fun flowAll(): Flow<List<DictRule>>

    @Query("select * from dictRules where name = :name")
    suspend fun getByName(name: String): DictRule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg dictRule: DictRule)

    @Update
    suspend fun update(vararg dictRule: DictRule)

    @Delete
    suspend fun delete(vararg dictRule: DictRule)

}
