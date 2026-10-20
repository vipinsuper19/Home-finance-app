package com.homefinance.app.data.repository

import com.homefinance.app.data.local.dao.AppSettingDao
import com.homefinance.app.data.local.entity.AppSettingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * In-memory [AppSettingDao] for JVM tests.
 *
 * A map behind a [MutableStateFlow] is the whole of it: the real table is a key/value store with no
 * behaviour to get wrong, so there is nothing here a fake could hide. What it lets a test do is set a
 * key's value without going anywhere near Room - which [CategorySeederTest] needs in order to present
 * a database that has already been seeded, and which the Settings tests need to prove that a written
 * preference really does reach the observable read.
 *
 * @param initial the keys to start with, so a test can say "this ran before" without writing anything.
 */
class FakeAppSettingDao(initial: Map<String, String> = emptyMap()) : AppSettingDao {

    private val state = MutableStateFlow<Map<String, String>>(LinkedHashMap(initial))

    /** Every setting passed to [put] or [putAll], in call order, exactly as it arrived. */
    val written: MutableList<AppSettingEntity> = mutableListOf()

    /** The keys currently stored, for a test that wants to assert on the rows themselves. */
    val stored: Map<String, String> get() = state.value

    /** When set, both reads fail with it, so a screen's failed-read state can be exercised. */
    var failure: Throwable? = null

    /** When set, every write fails with it, standing in for a database that cannot be written. */
    var writeFailure: Throwable? = null

    override suspend fun get(key: String): String? {
        failure?.let { throw it }
        return state.value[key]
    }

    override suspend fun put(setting: AppSettingEntity) {
        writeFailure?.let { throw it }
        state.update { current -> current + (setting.key to setting.value) }
        written += setting
    }

    override fun observeAll(): Flow<List<AppSettingEntity>> = flow {
        // Checked on collection rather than at construction, so a test can make a read fail after the
        // repository exists - which is the situation a screen actually hits.
        failure?.let { throw it }
        emitAll(state.map { values -> values.toEntities() })
    }

    override suspend fun getAll(): List<AppSettingEntity> {
        failure?.let { throw it }
        return state.value.toEntities()
    }

    override suspend fun putAll(settings: List<AppSettingEntity>) {
        settings.forEach { put(it) }
    }

    override suspend fun deleteAll() {
        state.value = emptyMap()
    }

    private fun Map<String, String>.toEntities(): List<AppSettingEntity> =
        entries.sortedBy { it.key }.map { (key, value) -> AppSettingEntity(key = key, value = value) }
}
