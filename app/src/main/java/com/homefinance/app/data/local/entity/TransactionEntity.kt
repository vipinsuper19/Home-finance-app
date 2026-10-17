package com.homefinance.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.homefinance.app.data.model.TransactionType
import java.time.Instant
import java.time.LocalDate

/**
 * One movement of money: a single income or expense entry in the household ledger.
 *
 * ### Money
 *
 * [amountPaise] is a positive [Long] count of **paise** (1 rupee = 100 paise). Money is never a
 * `Float` or a `Double`: binary floating point cannot represent `0.10` exactly, and a ledger that
 * is off by a paise is worse than useless. The direction of the movement is carried by [type],
 * not by the sign of the amount, so there is exactly one place that decides whether a number adds
 * to or subtracts from the household balance.
 *
 * The schema *cannot* express "amount must be positive" - Room has no `CHECK` constraint support -
 * so that rule is enforced by [com.homefinance.app.data.repository.TransactionRepository] before
 * every insert and update (see its KDoc for why a hand-written `CHECK` in a migration was
 * rejected).
 *
 * ### Quantity and rate
 *
 * An entry may additionally record *how much* was bought and *at what price*, which is how a
 * household actually remembers a milk bill. [quantityThousandths] is a whole number of thousandths of
 * a unit (1.5 litres is `1500`) and [ratePaise] is money per unit, so neither is ever a `Float` or a
 * `Double` either.
 *
 * Those two columns are **null together or set together**: a quantity without a rate says nothing
 * about the money. When they are set, [amountPaise] is exactly
 * `quantityThousandths x ratePaise / 1000` rounded half-up - the same function the form previews with
 * - and [com.homefinance.app.data.repository.TransactionRepository] refuses a row whose three figures
 * disagree. The amount stays the single figure the ledger is summed from, so how an entry was
 * *entered* never changes what it is *worth*.
 *
 * ### Dates and times
 *
 * - [transactionDate] is the day the money moved, as the household sees it: a plain calendar date
 *   with no time and no timezone, stored as an ISO epoch day. Dated 15 August means 15 August
 *   whatever the device's timezone later becomes.
 * - [createdAt] and [updatedAt] are absolute instants stored as UTC epoch milliseconds. They are
 *   audit metadata, never the user-facing date, and they are maintained by the repository.
 *
 * @param id surrogate key, allocated by SQLite on insert (`0` means "not yet inserted") and used
 *   to break ties so that listing is deterministic even for same-day entries.
 * @param type income or expense. Must match the [categoryId] category's own type.
 * @param amountPaise positive amount in paise. Always positive - see above.
 * @param categoryId the category this entry is filed under.
 * @param description optional free text, for example "August milk".
 * @param notes optional longer free text, kept separate from [description] so the one-line summary a
 *   ledger row shows is never the whole of what the household wrote.
 * @param quantityThousandths how many thousandths of a unit were bought, or `null` when the entry was
 *   recorded as a plain amount. Set together with [ratePaise].
 * @param ratePaise the price of one unit in paise, or `null` when the entry was recorded as a plain
 *   amount. Set together with [quantityThousandths].
 * @param transactionDate the calendar day the money moved.
 * @param createdAt when the row was first written.
 * @param updatedAt when the row was last edited.
 */
@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            // RESTRICT, never CASCADE: retiring or deleting a category must never take its
            // historical transactions with it.
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        // Supports the foreign key, and "everything filed under this category".
        Index(value = ["category_id"]),
        // Serves date-range scans and the newest-first ordering the ledger is read in.
        Index(value = ["transaction_date"]),
        // Serves "expenses between these dates" - the shape most reports will ask for.
        Index(value = ["type", "transaction_date"]),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "type")
    val type: TransactionType,

    @ColumnInfo(name = "amount_paise")
    val amountPaise: Long,

    @ColumnInfo(name = "category_id")
    val categoryId: Long,

    @ColumnInfo(name = "description")
    val description: String? = null,

    @ColumnInfo(name = "notes")
    val notes: String? = null,

    @ColumnInfo(name = "quantity_thousandths")
    val quantityThousandths: Long? = null,

    @ColumnInfo(name = "rate_paise")
    val ratePaise: Long? = null,

    @ColumnInfo(name = "transaction_date")
    val transactionDate: LocalDate,

    @ColumnInfo(name = "created_at")
    val createdAt: Instant,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
)
