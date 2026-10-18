package com.homefinance.app.data.repository

import com.homefinance.app.data.local.dao.AppSettingDao
import com.homefinance.app.data.local.entity.AppSettingEntity

/**
 * In-memory [AppSettingDao] for JVM tests.
 *
 * A plain map is the whole of it: the real table is a key/value store with no behaviour to get wrong,
 * so there is nothing here a fake could hide. What it lets a test do is set a key's value without
 * going anywhere near Room - which [CategorySeederTest] needs in order to present a database that
 * has already been seeded.
 *
 * @param initial the keys to start with, so a test can say "this ran before" without writing anything.
 */
class FakeAppSettingDao(initial: Map<String, String> = emptyMap()) : AppSettingDao {

    private val values: MutableMap<String, String> = LinkedHashMap(initial)

    /** Every setting passed to [put], in call order, exactly as it arrived. */
    val written: MutableList<AppSettingEntity> = mutableListOf()

    /** The keys currently stored, for a test that wants to assert on the rows themselves. */
    val stored: Map<String, String> get() = values.toMap()

    override suspend fun get(key: String): String? = values[key]

    override suspend fun put(setting: AppSettingEntity) {
        values[setting.key] = setting.value
        written += setting
    }
}
