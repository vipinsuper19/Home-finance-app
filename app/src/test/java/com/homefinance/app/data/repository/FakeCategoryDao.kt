package com.homefinance.app.data.repository

import com.homefinance.app.data.local.dao.CategoryDao
import com.homefinance.app.data.local.entity.CategoryEntity
import com.homefinance.app.data.model.TransactionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory [CategoryDao] for JVM tests.
 *
 * It deliberately does **not** emulate the unique index on `(type, name)`. A fake that re-created
 * the constraint would only ever prove that the fake works; duplicate prevention is a property of
 * the real schema and is asserted against a real in-memory Room database in
 * `com.homefinance.app.data.local.CategoryDaoTest` instead.
 *
 * Every row handed to [insert] is recorded in [inserted], so a test can assert exactly what the
 * repository asked the database to store.
 */
class FakeCategoryDao(initial: List<CategoryEntity> = emptyList()) : CategoryDao {

    private val rows: MutableMap<Long, CategoryEntity> =
        initial.associateByTo(LinkedHashMap()) { it.id }

    private var nextId: Long = (initial.maxOfOrNull { it.id } ?: 0L) + 1L

    /** Every entity passed to [insert], in call order, exactly as it arrived. */
    val inserted: MutableList<CategoryEntity> = mutableListOf()

    /**
     * When set, both observe flows fail with it.
     *
     * A category read is the one thing on the recurring form that can fail without the household doing
     * anything wrong, so a JVM test needs a way to say "this read is broken" without corrupting the
     * rows themselves.
     */
    var failure: Throwable? = null

    override suspend fun insert(category: CategoryEntity): Long {
        val id = if (category.id != 0L) category.id else nextId++
        rows[id] = category.copy(id = id)
        inserted += category
        return id
    }

    override suspend fun update(category: CategoryEntity) {
        rows[category.id] = category
    }

    override suspend fun getById(id: Long): CategoryEntity? = rows[id]

    override fun observeAll(): Flow<List<CategoryEntity>> {
        failure?.let { return flow { throw it } }
        return flowOf(rows.values.sortedWith(ACTIVE_FIRST_THEN_NAME))
    }

    override fun observeByType(type: TransactionType): Flow<List<CategoryEntity>> {
        failure?.let { return flow { throw it } }
        return flowOf(rows.values.filter { it.type == type }.sortedWith(ACTIVE_FIRST_THEN_NAME))
    }

    override suspend fun deactivate(id: Long) {
        rows[id]?.let { rows[id] = it.copy(isActive = false) }
    }

    private companion object {
        val ACTIVE_FIRST_THEN_NAME: Comparator<CategoryEntity> =
            compareByDescending<CategoryEntity> { it.isActive }.thenBy { it.name }
    }
}
