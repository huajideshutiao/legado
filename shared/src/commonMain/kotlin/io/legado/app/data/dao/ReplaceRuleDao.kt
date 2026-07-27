package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.dealGroups
import io.legado.app.data.entities.ReplaceRule
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map


@Dao
interface ReplaceRuleDao {

    @Query("SELECT * FROM replace_rules ORDER BY sortOrder ASC")
    fun flowAll(): Flow<List<ReplaceRule>>

    @Query("SELECT * FROM replace_rules where `group` like :key or name like :key ORDER BY sortOrder ASC")
    fun flowSearch(key: String): Flow<List<ReplaceRule>>

    @Query("SELECT * FROM replace_rules where `group` like :key ORDER BY sortOrder ASC")
    fun flowGroupSearch(key: String): Flow<List<ReplaceRule>>

    @Query("select `group` from replace_rules where `group` is not null and `group` <> ''")
    fun flowGroupsUnProcessed(): Flow<List<String>>

    @Query("select * from replace_rules where `group` is null or trim(`group`) = '' or trim(`group`) like '%未分组%'")
    fun flowNoGroup(): Flow<List<ReplaceRule>>

    @Query("SELECT MIN(sortOrder) FROM replace_rules")
    suspend fun minOrder(): Int

    @Query("SELECT MAX(sortOrder) FROM replace_rules")
    suspend fun maxOrder(): Int

    @Query("SELECT * FROM replace_rules ORDER BY sortOrder ASC")
    suspend fun all(): List<ReplaceRule>

    @Query("select distinct `group` from replace_rules where trim(`group`) <> ''")
    suspend fun allGroupsUnProcessed(): List<String>

    @Query("SELECT * FROM replace_rules WHERE isEnabled = 1 ORDER BY sortOrder ASC")
    suspend fun allEnabled(): List<ReplaceRule>

    @Query("SELECT * FROM replace_rules WHERE id = :id")
    suspend fun findById(id: Long): ReplaceRule?

    @Query("SELECT * FROM replace_rules WHERE id in (:ids)")
    suspend fun findByIds(vararg ids: Long): List<ReplaceRule>

    @Query(
        """SELECT * FROM replace_rules WHERE isEnabled = 1 and scopeContent = 1
        AND (scope LIKE '%' || :name || '%' or scope LIKE '%' || :origin || '%' or scope is null or scope = '')
        and (excludeScope is null or (excludeScope not LIKE '%' || :name || '%' and excludeScope not LIKE '%' || :origin || '%'))
        order by sortOrder"""
    )
    suspend fun findEnabledByContentScope(name: String, origin: String): List<ReplaceRule>

    @Query(
        """SELECT * FROM replace_rules WHERE isEnabled = 1 and scopeTitle = 1
        AND (scope LIKE '%' || :name || '%' or scope LIKE '%' || :origin || '%' or scope is null or scope = '')
        and (excludeScope is null or (excludeScope not LIKE '%' || :name || '%' and excludeScope not LIKE '%' || :origin || '%'))
        order by sortOrder"""
    )
    suspend fun findEnabledByTitleScope(name: String, origin: String): List<ReplaceRule>

    @Query("select * from replace_rules where `group` like '%' || :group || '%'")
    suspend fun getByGroup(group: String): List<ReplaceRule>

    @Query("select * from replace_rules where `group` is null or `group` = ''")
    suspend fun noGroup(): List<ReplaceRule>

    @Query("SELECT COUNT(*) - SUM(isEnabled) FROM replace_rules")
    suspend fun summary(): Int

    @Query("UPDATE replace_rules SET isEnabled = :enable")
    suspend fun enableAll(enable: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg replaceRule: ReplaceRule): List<Long>

    @Update
    suspend fun update(vararg replaceRules: ReplaceRule)

    @Delete
    suspend fun delete(vararg replaceRules: ReplaceRule)

    suspend fun allGroups(): List<String> = dealGroups(allGroupsUnProcessed())

    fun flowGroups(): Flow<List<String>> {
        return flowGroupsUnProcessed().map { list ->
            dealGroups(list)
        }.flowOn(IO)
    }
}
