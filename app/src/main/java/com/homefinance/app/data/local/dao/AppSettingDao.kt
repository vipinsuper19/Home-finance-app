package com.homefinance.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.homefinance.app.data.local.entity.AppSettingEntity

/**
 * Reads and writes for [AppSettingEntity], the table of one-off application facts.
 *
 * It is deliberately a two-statement interface. What lives in this table is not household data whose
 * history matters - it is a flag such as "the shipped categories have already been written" - so
 * there is no observable read here: nothing on screen is *about* a setting, and a `Flow` would only
 * wake a collector to tell it something it does not draw.
 *
 * [put] replaces: a setting has one current value, and the previous one is not history worth keeping.
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
}
