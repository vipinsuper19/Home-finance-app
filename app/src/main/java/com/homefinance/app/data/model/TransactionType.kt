package com.homefinance.app.data.model

/**
 * Whether money moved into the household (income) or out of it (expense).
 *
 * This is a closed set rather than a free-form string: an expense that is quietly recorded as an
 * unknown type would corrupt every total built on top of it, so the two directions are named
 * explicitly and stored as [name] (for example `"EXPENSE"`).
 */
enum class TransactionType {
    INCOME,
    EXPENSE,
    ;

    companion object {

        /**
         * Turns a value read back out of the database into an enum constant.
         *
         * Stored values are always one of the enum names, so anything else means the row was
         * written by something that does not respect this model. That is reported rather than
         * guessed at: silently falling back to [EXPENSE] would mis-state the household's money.
         *
         * @throws IllegalArgumentException if [stored] is not the name of a constant.
         */
        fun fromStorage(stored: String): TransactionType =
            entries.firstOrNull { it.name == stored }
                ?: throw IllegalArgumentException(
                    "Unknown stored transaction type '$stored'; expected one of " +
                        entries.joinToString { it.name },
                )
    }
}
