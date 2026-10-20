package com.homefinance.app.feature.recurring

import com.homefinance.app.data.local.relation.RecurringOccurrenceDetail
import com.homefinance.app.data.local.relation.RecurringScheduleWithProgress
import com.homefinance.app.data.repository.RecurringRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * One schedule *and* its occurrences, in the four shapes a screen needs.
 *
 * ### Why this is shared
 *
 * The details screen and the occurrence history are two screens reading the same two queries: the
 * schedule's own figures, and every occurrence of it. Splitting it per screen would mean two copies of
 * the same `combine`, the same loading/failed fold, and - worst of all - the same chance of the two
 * drifting apart about what "the schedule has gone" means.
 *
 * Both reads are the repository's own observable projections, so nothing here polls: Room pushes a new
 * pair whenever the schedule, one of its occurrences, or the category it is filed under changes. That is
 * what makes a payment recorded on one of these screens appear on the other with no cooperation from
 * either view model.
 *
 * ### Why `Missing` is its own member
 *
 * A schedule can genuinely be gone - it is the row the household tapped, and it cannot be deleted, but a
 * database restored underneath a running screen can still lose it. That is a different fact from "the
 * read failed", and it deserves a different screen: the first means there is nothing to look at, the
 * second means there might be and the app could not find out. Folding them together would tell a
 * household their schedule had gone when it was merely unreadable.
 */
internal sealed interface ScheduleAndOccurrencesRead {

    /** The first read, before the database has answered. */
    data object Loading : ScheduleAndOccurrencesRead

    /** The schedule is not there any more. */
    data object Missing : ScheduleAndOccurrencesRead

    /** The read failed; [cause] is kept so a test can prove which failure reached the screen. */
    data class Failed(val cause: Throwable) : ScheduleAndOccurrencesRead

    /**
     * The schedule with its occurrences in the DAO's order: waiting dates soonest first, then settled
     * history newest first.
     *
     * The occurrences are the raw projections rather than a screen's display type, because the two
     * screens want different slices of them - the details screen wants the outstanding one and a short
     * history, the history wants them all - and each resolves what it needs against its own "today".
     */
    data class Rows(
        val schedule: RecurringScheduleWithProgress,
        val occurrences: List<RecurringOccurrenceDetail>,
    ) : ScheduleAndOccurrencesRead
}

/**
 * The schedule [scheduleId] and its occurrences, as one flow.
 *
 * `catch` ends the flow on failure, so a broken read becomes a message with a retry rather than a
 * spinner that never stops; a failing view model restarts it by re-subscribing.
 */
internal fun RecurringRepository.observeScheduleAndOccurrences(
    scheduleId: Long,
): Flow<ScheduleAndOccurrencesRead> =
    combine(
        observeSchedule(scheduleId),
        observeOccurrences(scheduleId),
    ) { schedule, occurrences -> schedule to occurrences }
        .map<Pair<RecurringScheduleWithProgress?, List<RecurringOccurrenceDetail>>, ScheduleAndOccurrencesRead> { (schedule, occurrences) ->
            if (schedule == null) {
                ScheduleAndOccurrencesRead.Missing
            } else {
                ScheduleAndOccurrencesRead.Rows(schedule = schedule, occurrences = occurrences)
            }
        }
        .onStart { emit(ScheduleAndOccurrencesRead.Loading) }
        .catch { failure -> emit(ScheduleAndOccurrencesRead.Failed(failure)) }
