package com.homefinance.app.data.model

/**
 * Whether a recurring schedule is still expected to come round.
 *
 * Three states, and all three are *stored*, because they cannot be derived from anything else:
 *
 *  - [ACTIVE] - occurrences are generated up to the planning horizon and the household is expected
 *    to pay them.
 *  - [PAUSED] - no new occurrences are generated while it is paused, and the pause is remembered
 *    across restarts. Pausing never deletes the schedule and never touches the occurrences already
 *    generated: the household has merely stopped expecting new ones.
 *  - [RETIRED] - the schedule is finished with ("Stop" on the details screen). It is kept rather
 *    than deleted, because the occurrences already paid under it are history and the ledger entries
 *    they link to must keep a schedule to point at. Retiring is one-way on purpose: re-starting a
 *    finished schedule is a new schedule, with its own start date and its own history, not a
 *    resurrection of the old one.
 *
 * A schedule is a *plan*, never money. None of these states is ever expressed by deleting rows, and
 * none of them can be reached from the passage of time: a schedule is exactly as active as the
 * household last said it was, and no due date passing on its own moves it anywhere.
 */
enum class RecurringScheduleStatus {
    ACTIVE,
    PAUSED,
    RETIRED,
    ;

    companion object {

        /**
         * Turns a value read back out of the database into an enum constant.
         *
         * @throws IllegalArgumentException if [stored] is not the name of a constant.
         */
        fun fromStorage(stored: String): RecurringScheduleStatus =
            entries.firstOrNull { it.name == stored }
                ?: throw IllegalArgumentException(
                    "'$stored' is not a known schedule status; the state of a household's " +
                        "schedule must not be guessed at",
                )
    }
}
