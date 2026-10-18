package com.homefinance.app.data.model

/**
 * Where one scheduled occurrence of a recurring expense stands.
 *
 * Only the two **terminal** states are stored:
 *
 *  - [PENDING] - the occurrence is expected and nothing has been decided about it yet. Whether it
 *    is *upcoming*, *due today* or *overdue* is not stored: it is the same row, read on a different
 *    day, and a stored "overdue" flag would be wrong the moment the clock passed midnight without
 *    anything having written to the database. The display state is derived from [PENDING] plus the
 *    due date against today's local date, in
 *    [com.homefinance.app.feature.recurring.OccurrenceDisplayState].
 *  - [PAID] - the household recorded the payment, and an actual transaction exists that this
 *    occurrence links to. This is a fact about money, so it is stored.
 *  - [SKIPPED] - the household decided this occurrence never happened and never will. Stored for the
 *    same reason: it must not be generated again, and "no" is a decision the database cannot
 *    re-derive from a date.
 *
 * Both terminal states are final. Nothing in the app moves an occurrence back to [PENDING]: undoing a
 * payment would mean deleting the ledger entry it created, and undoing a skip would mean re-offering
 * an occurrence the household has already dismissed.
 */
enum class RecurringOccurrenceStatus {
    PENDING,
    PAID,
    SKIPPED,
    ;

    companion object {

        /**
         * Turns a value read back out of the database into an enum constant.
         *
         * @throws IllegalArgumentException if [stored] is not the name of a constant.
         */
        fun fromStorage(stored: String): RecurringOccurrenceStatus =
            entries.firstOrNull { it.name == stored }
                ?: throw IllegalArgumentException(
                    "'$stored' is not a known occurrence status; whether a bill was paid must " +
                        "never be guessed at",
                )
    }
}
