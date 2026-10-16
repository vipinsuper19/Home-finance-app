package com.homefinance.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.homefinance.app.data.model.TransactionType
import java.time.Instant

/**
 * A bucket that transactions are filed under: "Salary", "Groceries", "Electricity", and so on.
 *
 * A category belongs to exactly one direction of money - [TransactionType.INCOME] or
 * [TransactionType.EXPENSE] - because "Salary" is never an expense and "Groceries" is never
 * income. A transaction may therefore only reference a category of its own type (enforced by
 * [com.homefinance.app.data.repository.TransactionRepository]).
 *
 * ### Naming policy
 *
 * Names are **normalised** before they are stored: leading and trailing whitespace is dropped and
 * any internal run of whitespace collapses to a single space. Uniqueness is then enforced by the
 * unique index on `(type, name)`, which uses SQLite's `NOCASE` collation, so "Groceries" and
 * "groceries" cannot both exist for the same type. Capitalisation is deliberately *kept* as the
 * household typed it - lower-casing the stored value would fight the user - and `NOCASE` only
 * folds ASCII case. [normalizeName] is the single definition of the policy; callers go through
 * [create] so it cannot be skipped by accident.
 *
 * Uniqueness is a database constraint rather than an application check on purpose: a
 * "look for a duplicate, then insert" pair of statements is two statements, and two statements
 * can interleave. The index cannot be raced.
 *
 * ### Deletion policy
 *
 * Categories are **never hard-deleted**. [isActive] is flipped to `false` instead, so the
 * transactions already filed under the category keep their history and their grouping. The
 * foreign key on [TransactionEntity] additionally refuses to delete a category that still has
 * transactions, so even a mistaken `DELETE` cannot cascade a household's ledger into oblivion.
 *
 * @param id surrogate key, allocated by SQLite on insert (`0` means "not yet inserted").
 * @param name the display name, already normalised by [create] / [normalizeName].
 * @param type which side of the ledger this category belongs to.
 * @param isActive `false` once the category has been retired: it stays visible on historical
 *   transactions but is offered for new ones only when this is `true`.
 * @param createdAt audit timestamp (UTC instant) for when the row was written.
 */
@Entity(
    tableName = "categories",
    indices = [
        // One index does two jobs: it forbids duplicate names within a type, and because `type`
        // is its leading column it also serves "categories of this type" lookups.
        Index(value = ["type", "name"], unique = true),
    ],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "name", collate = ColumnInfo.NOCASE)
    val name: String,

    @ColumnInfo(name = "type")
    val type: TransactionType,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,

    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
) {
    companion object {

        /**
         * The one definition of the category naming policy: trim the ends, and collapse any run
         * of inner whitespace to a single space. `"  Milk   &  Curd "` becomes `"Milk & Curd"`.
         *
         * Note that `\s` is ASCII whitespace, which is what a keyboard produces. This does not
         * fold case; case-insensitivity comes from the column's `NOCASE` collation.
         */
        fun normalizeName(raw: String): String = raw.trim().replace(WHITESPACE_RUN, SINGLE_SPACE)

        /**
         * Normalises [raw] and rejects a name that is empty once normalised.
         *
         * @throws IllegalArgumentException if [raw] is blank or only whitespace.
         */
        fun requireValidName(raw: String): String {
            val normalized = normalizeName(raw)
            require(normalized.isNotEmpty()) { "Category name must not be blank" }
            return normalized
        }

        /** Builds a new category with a validated, normalised [name]. */
        fun create(
            name: String,
            type: TransactionType,
            createdAt: Instant = Instant.now(),
        ): CategoryEntity = CategoryEntity(
            name = requireValidName(name),
            type = type,
            createdAt = createdAt,
        )

        private const val SINGLE_SPACE = " "
        private val WHITESPACE_RUN = Regex("\\s+")
    }
}
