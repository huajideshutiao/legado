package io.legado.app.data.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import io.legado.app.data.entities.KeywordHighlight
import kotlinx.coroutines.flow.Flow

@Dao
interface KeywordHighlightDao {

    @Query("select * from keywordHighlights order by sortOrder, id")
    suspend fun all(): List<KeywordHighlight>

    @Query("select * from keywordHighlights where isEnabled = 1 order by sortOrder, id")
    suspend fun enabled(): List<KeywordHighlight>

    @Query("select * from keywordHighlights order by sortOrder, id")
    fun flowAll(): Flow<List<KeywordHighlight>>

    @Query("select * from keywordHighlights where isEnabled = 1 order by sortOrder, id")
    fun flowEnabled(): Flow<List<KeywordHighlight>>

    @Query("select * from keywordHighlights where id = :id")
    suspend fun findById(id: Long): KeywordHighlight?

    @Query("select IFNULL(MAX(sortOrder), 0) from keywordHighlights")
    suspend fun maxOrder(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg rule: KeywordHighlight)

    @Update
    suspend fun update(vararg rule: KeywordHighlight)

    @Delete
    suspend fun delete(vararg rule: KeywordHighlight)

    @Query("delete from keywordHighlights")
    suspend fun deleteAll()
}
