package com.homefinance.app.data.repository

import com.homefinance.app.core.util.QuantityInput
import com.homefinance.app.data.local.dao.CategoryDao
import com.homefinance.app.data.local.dao.TransactionDao
import com.homefinance.app.data.local.entity.TransactionEntity
import com.homefinance.app.data.local.relation.TransactionWithCategory
import com.homefinance.app.data.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/**
 * The sanctioned way for the rest of the app to read and write transactions.
 *
 * ### Why this exists at all
 *
 * Two of the rules a transaction must obey span more than the row itself, and a single-table Room
 * DAO cannot express either of them:
 *
 *  1. **Amounts are positive.** Room has no `CHECK` constraint support, so `amount_paise` is a
 *     plain `INTEGER NOT NULL` as far as the schema is concerned, and `-1` would be accepted. The
 *     direction of the movement lives in `type`, never in the sign of the amount, so a negative
 *     amount is always a bug. [requirePositiveAmount] is the only gate.
 *  2. **A transaction's category must exist and must be of the same type.** `"Salary"` is an
 *     income category, so an expense filed under it is nonsense even though both rows are
 *     individually valid.
 *  3. **A quantity and a rate are recorded together, and the amount is exactly their product.** A
 *     quantity with no rate says nothing about the money, and a stored amount that disagreed with
 *     the quantity and rate beside it would make the row contradict itself. Both halves are checked
 *     here against the one multiplication in [QuantityInput.amountPaise].
 *
 * ### How honestly this is enforced
 *
 * All three rules are checked here, immediately before the write. The foreign key from
 * `transactions.category_id` to `categories.id` is a second, independent backstop for the
 * "category must exist" half of rule 2 - see [TransactionEntity].
 *
 * The *rest* of rule 2 - that the category's type matches the transaction's - is not expressible
 * as a single-column foreign key. It could be pushed entirely into the schema with a composite
 * foreign key from `transactions(category_id, type)` to `categories(id, type)`, which would
 * require the schema to carry an extra unique index on `categories(id, type)`. That was
 * considered and rejected: the repository has to make the check anyway to report *which* rule
 * was broken in terms the UI can show, and two enforcement points that can drift apart are worse
 * than one that is tested. So the guarantee is: **every write made through this repository
 * satisfies both rules**, and that is asserted by tests.
 *
 * Finally, because categories are never hard-deleted ([CategoryRepository.deactivate] retires
 * them instead), the category read that precedes an insert cannot be invalidated before the
 * write lands.
 *
 * ### Audit timestamps
 *
 * [TransactionEntity.createdAt] and [TransactionEntity.updatedAt] are owned by this class, not by
 * callers. [add] stamps both; [update] re-stamps `updatedAt` so an edit can never forget to. That
 * is why [update] overwrites whatever the caller passed in that field.
 */
class TransactionRepository(
    private val transactionDao: TransactionDao,
    private val categoryDao: CategoryDao,
) {

    /** The whole ledger, newest first. */
    fun observeAll(): Flow<List<TransactionEntity>> = transactionDao.observeAll()

    /**
     * The whole ledger, newest first, each row carrying the name of its category.
     *
     * This is what the transaction list reads. It is a pass-through with no rules of its own:
     * reading has no invariants to defend, and the join is expressed once in the DAO so the
     * screen never has to resolve a category per row.
     */
    fun observeAllWithCategory(): Flow<List<TransactionWithCategory>> =
        transactionDao.observeAllWithCategory()

    /** Transactions dated between [from] and [to] inclusive, newest first. */
    fun observeInDateRange(from: LocalDate, to: LocalDate): Flow<List<TransactionEntity>> =
        transactionDao.observeInDateRange(from, to)

    /** [type] transactions dated between [from] and [to] inclusive, newest first. */
    fun observeByTypeInDateRange(
        type: TransactionType,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<TransactionEntity>> = transactionDao.observeByTypeInDateRange(type, from, to)

    suspend fun getById(id: Long): TransactionEntity? = transactionDao.getById(id)

    /**
     * Records a transaction and returns its new `id`.
     *
     * @param amountPaise positive amount in **paise**; the direction comes from [type], not from a
     *   sign. When [quantityThousandths] and [ratePaise] are given this must be exactly their
     *   product.
     * @param description optional free text; trimmed, and stored as `null` when blank.
     * @param notes optional longer free text; normalised exactly as [description] is.
     * @param quantityThousandths how many thousandths of a unit were bought, or `null` for an entry
     *   recorded as a plain amount. Must be given together with [ratePaise].
     * @param ratePaise the price of one unit in paise, or `null`. Must be given together with
     *   [quantityThousandths].
     * @param now injection point for tests so audit timestamps are deterministic.
     *
     * @throws IllegalArgumentException if [amountPaise] is zero or negative, if no category has
     *   [categoryId], if that category's type is not [type], or if the quantity, the rate and the
     *   amount do not agree.
     * @throws android.database.sqlite.SQLiteConstraintException if [categoryId] is removed between
     *   the check and the insert.
     */
    suspend fun add(
        type: TransactionType,
        amountPaise: Long,
        categoryId: Long,
        transactionDate: LocalDate,
        description: String? = null,
        notes: String? = null,
        quantityThousandths: Long? = null,
        ratePaise: Long? = null,
        now: Instant = Instant.now(),
    ): Long {
        requirePositiveAmount(amountPaise)
        requireCategoryOfType(categoryId, type)
        requireQuantityAndRateAgree(amountPaise, quantityThousandths, ratePaise)

        return transactionDao.insert(
            TransactionEntity(
                type = type,
                amountPaise = amountPaise,
                categoryId = categoryId,
                description = description.normalizedOrNull(),
                notes = notes.normalizedOrNull(),
                quantityThousandths = quantityThousandths,
                ratePaise = ratePaise,
                transactionDate = transactionDate,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /**
     * Rewrites the transaction whose `id` matches [transaction], after re-validating it against
     * the same rules as [add].
     *
     * [transaction]'s `updatedAt` is ignored and replaced with [now].
     *
     * @throws IllegalArgumentException if the amount is not positive, if the category is missing,
     *   if its type does not match, or if the quantity, the rate and the amount do not agree.
     */
    suspend fun update(transaction: TransactionEntity, now: Instant = Instant.now()) {
        requirePositiveAmount(transaction.amountPaise)
        requireCategoryOfType(transaction.categoryId, transaction.type)
        requireQuantityAndRateAgree(
            amountPaise = transaction.amountPaise,
            quantityThousandths = transaction.quantityThousandths,
            ratePaise = transaction.ratePaise,
        )

        transactionDao.update(
            transaction.copy(
                description = transaction.description.normalizedOrNull(),
                notes = transaction.notes.normalizedOrNull(),
                updatedAt = now,
            ),
        )
    }

    /** Removes one transaction the caller explicitly selected. */
    suspend fun delete(transaction: TransactionEntity) = transactionDao.delete(transaction)

    private fun requirePositiveAmount(amountPaise: Long) {
        require(amountPaise > 0L) {
            "A transaction amount must be positive; the direction of the movement is carried by " +
                "its type, so a zero or negative amount ($amountPaise paise) is always a bug"
        }
    }

    private suspend fun requireCategoryOfType(categoryId: Long, type: TransactionType) {
        val category = categoryDao.getById(categoryId)
            ?: throw IllegalArgumentException(
                "No category with id $categoryId; a transaction must reference an existing " +
                    "category",
            )

        require(category.type == type) {
            "Category '${category.name}' is a ${category.type} category, so it cannot hold a " +
                "$type transaction"
        }
    }

    /**
     * Enforces rule 3: the two halves of a quantity-and-rate entry travel together, and the amount is
     * exactly what they multiply to.
     *
     * The comparison is against [QuantityInput.amountPaise] - the same function the form uses to show
     * the household what will be saved - so "the form said ₹90.00" and "the database refuses anything
     * but 9000 paise" cannot drift apart. A row with a quantity and a rate but a hand-typed amount
     * would let the ledger claim to have been entered as `1.5 x ₹60` while holding a different figure,
     * which is precisely the contradiction this refuses.
     *
     * An exact mismatch (an amount that does not fit a `Long` at all) comes back from
     * [QuantityInput.amountPaise] as `null` and simply cannot equal a `Long`, so it is refused by the
     * same comparison.
     */
    private fun requireQuantityAndRateAgree(
        amountPaise: Long,
        quantityThousandths: Long?,
        ratePaise: Long?,
    ) {
        require((quantityThousandths == null) == (ratePaise == null)) {
            "A quantity and a rate are recorded together or not at all, but got " +
                "quantity=$quantityThousandths and rate=$ratePaise"
        }

        if (quantityThousandths == null || ratePaise == null) return

        require(quantityThousandths > 0L && ratePaise > 0L) {
            "A quantity and a rate are magnitudes, but got quantity=$quantityThousandths and " +
                "rate=$ratePaise"
        }

        val derived = QuantityInput.amountPaise(quantityThousandths, ratePaise)
        require(derived == amountPaise) {
            "$quantityThousandths thousandths at $ratePaise paise each is $derived paise, which " +
                "is not the $amountPaise paise this row claims to be worth"
        }
    }
}

/** Trims, and treats all-whitespace free text as "nothing written" rather than as `"   "`. */
private fun String?.normalizedOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
