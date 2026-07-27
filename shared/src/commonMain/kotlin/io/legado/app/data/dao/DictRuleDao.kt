package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
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
