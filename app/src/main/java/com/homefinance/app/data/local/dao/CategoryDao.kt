package com.homefinance.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.homefinance.app.data.local.entity.CategoryEntity
import com.homefinance.app.data.model.TransactionType
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes for [CategoryEntity].
 *
 * Every query is parameterised - no SQL is ever assembled from a caller's string - and every
 * observable read returns a [Flow], so the database pushes changes instead of the UI polling.
 *
 * Ordering is always `is_active DESC, name ASC`: active categories first (they are the ones a
 * picker offers) and then alphabetically with `NOCASE` collation, so the list does not reshuffle
 * according to capitalisation.
 */
@Dao
interface CategoryDao {

    /**
     * Inserts a category and returns its new `id`.
     *
     * [OnConflictStrategy.ABORT] is deliberate: the unique index on `(type, name)` is what makes
     * duplicate names impossible, and letting the violation surface as a failure is the point.
     * Ignoring or replacing the conflicting row instead would silently discard a category the
     * household is already using.
     *
     * Callers should build the row with [CategoryEntity.create], which normalises the name.
     *
     * @throws android.database.sqlite.SQLiteConstraintException if a category with the same
     *   normalised name already exists for the same type.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(category: CategoryEntity): Long

    /**
     * Inserts several categories at once, **ignoring** any whose `(type, name)` is already taken.
     *
     * This is the same unique index as [insert], read the other way round, and it exists for exactly
     * one caller: seeding the categories the app ships with. That caller must never fail because the
     * household already wrote a "Milk" of their own, and it must never overwrite one either - so the
     * name that is already there wins and the row the household wrote keeps its own values.
     *
     * The returned list has one entry per input, in order: the new row's `id`, or `-1` where that
     * category was already present. A caller can therefore count what it actually created without a
     * second read.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(categories: List<CategoryEntity>): List<Long>

    /** Replaces the stored row with the same `id`. */
    @Update
    suspend fun update(category: CategoryEntity)

    /** Returns the category with [id], or `null` if there is none. */
    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    /**
     * Observes every category, active ones first. Retired categories are still included: callers
     * tell them apart by [CategoryEntity.isActive] so history can still be labelled.
     */
    @Query("SELECT * FROM categories ORDER BY is_active DESC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    /**
     * Observes the single category with [id], or emits `null` when there is no such category.
     *
     * A `Flow` rather than a one-shot read because a screen can be *about* one category while the
     * household renames or retires it elsewhere: Room re-runs this and the heading changes with it,
     * instead of the screen going on showing the name the category had when it was opened.
     *
     * `null` is a value here, not an error. Every transaction references a category that cannot be
     * deleted (see [CategoryEntity]'s deletion policy), so a missing one means a caller asked about
     * an id that has never existed - which the screen answers with a sentence rather than with a
     * failure.
     */
    @Query("SELECT * FROM categories WHERE id = :id")
    fun observeById(id: Long): Flow<CategoryEntity?>

    /** Observes the categories that belong to [type], active ones first. */
    @Query(
        "SELECT * FROM categories WHERE type = :type " +
            "ORDER BY is_active DESC, name COLLATE NOCASE ASC",
    )
    fun observeByType(type: TransactionType): Flow<List<CategoryEntity>>

    /**
     * Retires a category without deleting it, so the transactions already filed under it keep
     * their grouping. Re-activating is the same statement with `is_active = 1`.
     *
     * There is deliberately no `delete`: see the deletion policy on [CategoryEntity].
     */
    @Query("UPDATE categories SET is_active = 0 WHERE id = :id")
    suspend fun deactivate(id: Long)
}
