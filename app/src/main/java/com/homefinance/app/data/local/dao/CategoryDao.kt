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
