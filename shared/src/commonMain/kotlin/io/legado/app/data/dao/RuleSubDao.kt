package io.legado.app.data.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import io.legado.app.data.entities.RuleSub
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleSubDao {

    @Query("select * from ruleSubs order by customOrder")
    suspend fun all(): List<RuleSub>

    @Query("select * from ruleSubs order by customOrder")
    fun flowAll(): Flow<List<RuleSub>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg ruleSub: RuleSub)

    @Delete
    suspend fun delete(vararg ruleSub: RuleSub)

    @Update
    suspend fun update(vararg ruleSub: RuleSub)
}
