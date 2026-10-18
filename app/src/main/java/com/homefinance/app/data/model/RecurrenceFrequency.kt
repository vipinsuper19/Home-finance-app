package com.homefinance.app.data.model

/**
 * How often a recurring schedule comes round: daily, weekly, monthly or yearly.
 *
 * This is a closed set rather than free text for the same reason [TransactionType] is: a stored
 * value that no longer means anything would silently turn a household's rent into something the
 * engine cannot place on a calendar, and a schedule that cannot be placed on a calendar is a bill
 * that is never generated and therefore never paid. An unrecognised value is reported by
 * [fromStorage] rather than guessed at.
 *
 * ### The arithmetic is not here
 *
 * A frequency says *how often*, not *which day*: "monthly" is not the same as "31 days later", and
 * the difference is exactly the bug this model refuses to have. The calendar work - anchoring every
 * occurrence to the schedule's start date, and clamping a 31st to the end of a shorter month -
 * lives in [com.homefinance.app.core.util.RecurrenceCalculator], which is pure and testable without
 * a device. The stored column is the enum's [name] (`"MONTHLY"`), never an ordinal, so inserting a
 * constant into the middle of this enum cannot re-label a household's existing rows.
 */
enum class RecurrenceFrequency {
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY,
    ;

    companion object {

        /**
         * Turns a value read back out of the database into an enum constant.
         *
         * @throws IllegalArgumentException if [stored] is not the name of a constant.
         */
        fun fromStorage(stored: String): RecurrenceFrequency =
            entries.firstOrNull { it.name == stored }
                ?: throw IllegalArgumentException(
                    "'$stored' is not a known recurrence frequency; a schedule whose rhythm " +
                        "cannot be named cannot be put on a calendar",
                )
    }
}
