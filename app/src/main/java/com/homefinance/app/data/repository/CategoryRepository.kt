package com.homefinance.app.data.repository

import com.homefinance.app.data.local.dao.CategoryDao
import com.homefinance.app.data.local.entity.CategoryEntity
import com.homefinance.app.data.model.TransactionType
import kotlinx.coroutines.flow.Flow

/**
 * The sanctioned way for the rest of the app to read and write categories.
 *
 * ### Why this exists at all
 *
 * A Room DAO cannot enforce two of the rules a category has to obey, so this thin layer does:
 *
 *  1. **Names must be normalised before they are compared.** The unique index on
 *     `(type, name)` only stops duplicates it can actually see, so `"Milk "` and `"Milk"` would
 *     otherwise both be storable. [CategoryEntity.create] applies the single normalisation
 *     policy, and this repository is where a category the household writes is built. The one other
 *     writer is [CategorySeeder], which builds the shipped categories through the same
 *     [CategoryEntity.create] and never touches a name already in use.
 *  2. **A blank name is not a database error.** `""` satisfies `NOT NULL` and is even unique, so
 *     the schema would happily store a category the household can never recognise.
 *
 * ### Duplicate names
 *
 * Deliberately *not* pre-checked with a "does this already exist?" query. That check and the
 * insert are two separate statements and could interleave; the unique index is a single atomic
 * statement and cannot be raced. A duplicate therefore surfaces as the
 * `SQLiteConstraintException` that [CategoryDao.insert] documents, and callers are expected to
 * show it as "that category already exists".
 *
 * ### Retiring versus deleting
 *
 * There is no `delete` here on purpose. Turning a category off leaves every transaction already
 * filed under it intact; deleting it would either orphan those rows or, with a cascading foreign
 * key, destroy them. See [CategoryEntity]'s deletion policy.
 *
 * The DAO is injected as an interface rather than taken from the database, which is what keeps
 * this class's rules testable without a device.
 */
class CategoryRepository(private val categoryDao: CategoryDao) {

    /** Every category, active first; retired ones stay in the list so history keeps its labels. */
    fun observeAll(): Flow<List<CategoryEntity>> = categoryDao.observeAll()

    /** The categories belonging to [type], active first. */
    fun observeByType(type: TransactionType): Flow<List<CategoryEntity>> =
        categoryDao.observeByType(type)

    suspend fun getById(id: Long): CategoryEntity? = categoryDao.getById(id)

    /**
     * Creates a category and returns its new `id`, normalising [name] first.
     *
     * @throws IllegalArgumentException if [name] is blank.
     * @throws android.database.sqlite.SQLiteConstraintException if [type] already has a category
     *   with that name (compared case-insensitively).
     */
    suspend fun create(name: String, type: TransactionType): Long =
        categoryDao.insert(CategoryEntity.create(name = name, type = type))

    /**
     * Renames the category with [id], normalising [name] first.
     *
     * @return `false` if no category has that [id]; `true` if a row was updated.
     * @throws IllegalArgumentException if [name] is blank.
     * @throws android.database.sqlite.SQLiteConstraintException if the new name collides with
     *   another category of the same type.
     */
    suspend fun rename(id: Long, name: String): Boolean {
        val existing = categoryDao.getById(id) ?: return false
        val renamed = existing.copy(name = CategoryEntity.requireValidName(name))
        categoryDao.update(renamed)
        return true
    }

    /**
     * Retires the category with [id] so it is no longer offered for new transactions, while every
     * transaction already filed under it keeps pointing at a row that still exists.
     */
    suspend fun deactivate(id: Long) = categoryDao.deactivate(id)

    /**
     * Offers a retired category again, so it can be chosen for new entries.
     *
     * The inverse of [deactivate] and equally free of destruction: every transaction already filed
     * under the category keeps pointing at the same row, and the name is untouched.
     */
    suspend fun activate(id: Long) = categoryDao.activate(id)
}
