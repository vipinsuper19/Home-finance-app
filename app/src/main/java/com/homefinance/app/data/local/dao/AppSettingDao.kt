package com.homefinance.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.homefinance.app.data.local.entity.AppSettingEntity
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes for [AppSettingEntity], the table of application preferences and one-off facts.
 *
 * The settings screen made this table user-facing: it is now the single place a theme, a date layout,
 * a display currency and three switches are stored, so it gained an observable read. That read is
 * what lets a change reach the whole app without a restart - the shell collects it, and every screen
 * that draws a preference is below the shell.
 *
 * [put] replaces: a setting has one current value, and the previous one is not history worth keeping.
 * That is also why there is no delete-one wrapper in the repository beyond the whole-table
 * [deleteAll] used by a restore and by "Clear app data".
 */
@Dao
interface AppSettingDao {

    /** The stored value of [key], or `null` when nothing has ever been written under it. */
    @Query("SELECT setting_value FROM app_setting WHERE setting_key = :key")
    suspend fun get(key: String): String?

    /**
     * Writes [setting], replacing any value already stored under the same key.
     *
     * `REPLACE` rather than `ABORT` because every writer here is recording the *current* state of a
     * one-off fact, not competing to create a row: the second write is the same fact said again.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(setting: AppSettingEntity)

    /** Every setting, ordered by key so the emitted list is stable between reads. */
    @Query("SELECT * FROM app_setting ORDER BY setting_key ASC")
    fun observeAll(): Flow<List<AppSettingEntity>>

    /** A one-shot read of every setting, for a backup written outside a `Flow` collection. */
    @Query("SELECT * FROM app_setting ORDER BY setting_key ASC")
    suspend fun getAll(): List<AppSettingEntity>

    /**
     * Writes [settings] in one statement group, replacing any key already present.
     *
     * A restore writes the backup's settings through this: the file is the whole state of the app's
     * preferences, so a key the backup does not mention must not survive beside it. The restore
     * therefore deletes the table first and writes these rows inside the same transaction.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(settings: List<AppSettingEntity>)

    /** Empties the table. Used by a restore, which replaces rather than merges, and by "Clear app data". */
    @Query("DELETE FROM app_setting")
    suspend fun deleteAll()
}
