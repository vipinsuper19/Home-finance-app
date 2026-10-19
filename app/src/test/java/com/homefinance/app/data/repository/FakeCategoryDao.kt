package com.homefinance.app.data.repository

import com.homefinance.app.data.local.dao.CategoryDao
import com.homefinance.app.data.local.entity.CategoryEntity
import com.homefinance.app.data.model.TransactionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * In-memory [CategoryDao] for JVM tests.
 *
 * It deliberately does **not** emulate the unique index on `(type, name)`. A fake that re-created
 * the constraint would only ever prove that the fake works; duplicate prevention is a property of
 * the real schema and is asserted against a real in-memory Room database in
 * `com.homefinance.app.data.local.CategoryDaoTest` instead.
 *
 * The one exception is [insertIgnoring], whose *contract* - skip a name that is already taken, report
 * `-1` for it - is what [CategorySeeder]'s rule is written against, so a fake that inserted the row
 * anyway would let the seeder look correct while it duplicated a household's category. What is
 * emulated is only the contract of that one method; the constraint behind it is still asserted
 * against Room.
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

    /** The rows currently stored, for a test that wants to assert on the categories themselves. */
    val storedCategories: List<CategoryEntity> get() = rows.values.toList()

    /**
     * When set, both observe flows fail with it.
     *
     * A category read is the one thing on the recurring form that can fail without the household doing
     * anything wrong, so a JVM test needs a way to say "this read is broken" without corrupting the
     * rows themselves.
     */
    var failure: Throwable? = null

    /**
     * A counter every write bumps, which only [observeById] observes.
     *
     * The category report's heading is read from this row, so a rename has to reach it - the same
     * invalidation Room performs when the `categories` table is written. The other two observes stay
     * on [flowOf], so nothing a test already relies on changes shape.
     */
    private val revision = MutableStateFlow(0)

    override suspend fun insert(category: CategoryEntity): Long {
        val id = if (category.id != 0L) category.id else nextId++
        rows[id] = category.copy(id = id)
        inserted += category
        revision.value += 1
        return id
    }

    /**
     * Inserts what is not already there and reports `-1` for what is, comparing names the way the
     * `NOCASE` unique index does.
     *
     * The existing row is left completely untouched - not renamed, not re-marked, not reactivated -
     * which is the part of the seeder's promise this fake has to be able to prove.
     */
    override suspend fun insertIgnoring(categories: List<CategoryEntity>): List<Long> =
        categories.map { category ->
            val alreadyThere = rows.values.any {
                it.type == category.type && it.name.equals(category.name, ignoreCase = true)
            }

            if (alreadyThere) {
                ALREADY_PRESENT
            } else {
                val id = if (category.id != 0L) category.id else nextId++
                rows[id] = category.copy(id = id)
                inserted += category
                revision.value += 1
                id
            }
        }

    override suspend fun update(category: CategoryEntity) {
        rows[category.id] = category
        revision.value += 1
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
        rows[id]?.let {
            rows[id] = it.copy(isActive = false)
            revision.value += 1
        }
    }

    /**
     * One category by id, so a report can name what it is about.
     *
     * Re-read on every write like the real query rather than answered once, because a category
     * renamed while its report is open has to reach the heading on screen.
     */
    override fun observeById(id: Long): Flow<CategoryEntity?> = revision.map {
        failure?.let { throw it }
        rows[id]
    }

    private companion object {
        val ACTIVE_FIRST_THEN_NAME: Comparator<CategoryEntity> =
            compareByDescending<CategoryEntity> { it.isActive }.thenBy { it.name }

        /** What Room reports for a row an `OnConflictStrategy.IGNORE` insert did not write. */
        const val ALREADY_PRESENT: Long = -1L
    }
}
