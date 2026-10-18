package com.homefinance.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single application preference, stored as a key/value pair.
 *
 * Room refuses to compile a database that declares no entities ("@Database annotation must
 * specify list of entities"), and Task 2.1 may not introduce financial tables yet. This is the
 * smallest role player for that requirement that is also genuinely useful: it is not tied to
 * income, expenses or any other financial concept, and the app will need somewhere to keep
 * things such as the chosen display currency long before the Settings screen is built. Adding it
 * now means version 1 of the schema does not have to be torn up later.
 *
 * There is no empty-database trickery here: [AppSettingDao] reads and writes it one key at a time,
 * and the only value stored so far is the flag that says the categories the app ships with have
 * already been written.
 */
@Entity(tableName = "app_setting")
data class AppSettingEntity(
    @PrimaryKey
    @ColumnInfo(name = "setting_key")
    val key: String,
    @ColumnInfo(name = "setting_value")
    val value: String,
)
