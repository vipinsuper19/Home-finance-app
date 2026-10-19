package com.homefinance.app.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.homefinance.app.R
import com.homefinance.app.core.ui.theme.OnUtilityContainer
import com.homefinance.app.core.ui.theme.UtilityContainer
import com.homefinance.app.core.util.CurrencyFormatter
import com.homefinance.app.core.util.DateFormatter
import com.homefinance.app.core.util.EnglishOrdinal
import com.homefinance.app.data.model.RecurrenceFrequency
import com.homefinance.app.data.model.RecurringScheduleStatus
import com.homefinance.app.feature.addtransaction.CategoryMark
import com.homefinance.app.feature.recurring.ActionErrorStrip
import com.homefinance.app.feature.recurring.OccurrenceDisplayState
import com.homefinance.app.feature.recurring.OccurrenceStateChip
import com.homefinance.app.feature.recurring.RecurringEmptyNote
import com.homefinance.app.feature.recurring.RecurringLoadingSpinner
import com.homefinance.app.feature.recurring.frequencySummary
import com.homefinance.app.feature.recurring.labelRes
import kotlin.math.abs

/**
 * The Home dashboard's recurring section: what is late, what is due today, and what is coming.
 *
 * ### It is a *reading*, not a to-do list
 *
 * Every date here is an occurrence the engine generated - an expectation, not a payment - so the card
 * says when each falls and how much was expected, and nothing more. It offers no "mark paid" of its
 * own: settling a date is a decision with an amount attached, and it belongs on the screen that states
 * the date, the figure and the category before anything is written. Tapping a row therefore opens that
 * schedule, which is where the household can act on it, and "View All" opens the tab that lists
 * everything.
 *
 * Amounts are drawn in the ordinary text colour rather than in the green and red the ledger uses, and
 * that is a deliberate distinction rather than an oversight: green and red mean *money that moved*.
 * A bill that has not been paid has moved nothing, and dressing it in the colour of a payment would
 * blur the one line this section exists to keep clear - see
 * [com.homefinance.app.data.local.relation.OutstandingOccurrence].
 *
 * ### What each group means
 *
 * Overdue is the only group that is asking to be noticed, so it is drawn first, in the error colours,
 * and in words ("3 days overdue") that do not depend on telling two colours apart. Due today is the
 * amber a household associates with a bill that has arrived but is not late. Upcoming is the quiet one:
 * a date that is simply ahead, with how far ahead it is.
 *
 * ### Dates that outlive their schedule
 *
 * A paused or stopped schedule's waiting dates are drawn like anyone else's, and that is deliberate.
 * Pausing stops the engine making more dates and stopping stops it for good, but neither deletes what
 * was already generated, and the app goes on offering to record those dates - so a household that
 * paused a schedule still owes whatever it had already been asked for. The row says which state its
 * schedule is in ("Monthly (on 5th) · Paused") rather than the section quietly dropping the bill: see
 * [com.homefinance.app.data.local.dao.RecurringDao.observeOutstandingOccurrences].
 *
 * @param state what the section is showing; loading, ready and failed are mutually exclusive.
 * @param onOpenRecurring opens the recurring tab - the section's heading, each group, and the empty
 *   state's invitation all lead there.
 * @param onOpenSchedule opens one date's schedule, where the date can actually be settled.
 * @param onAddSchedule opens the form for a first schedule, offered only when there are none at all.
 * @param onRetry retries a read that failed, and the engine run that may have failed with it.
 */
@Composable
internal fun RecurringPaymentsCard(
    state: HomeRecurringUiState,
    onOpenRecurring: () -> Unit,
    onOpenSchedule: (Long) -> Unit,
    onAddSchedule: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            RecurringSectionHeader(
                // Nothing to look at in a household that has never set a schedule up, and offering to
                // take them to an empty list would be a worse answer than the invitation below.
                showViewAll = (state as? HomeRecurringUiState.Ready)?.isFreshHousehold != true,
                onViewAll = onOpenRecurring,
            )

            when (state) {
                HomeRecurringUiState.Loading -> RecurringLoadingSpinner(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                )

                is HomeRecurringUiState.Failed -> FailedSection(onRetry = onRetry)

                is HomeRecurringUiState.Ready -> ReadySection(
                    state = state,
                    onOpenRecurring = onOpenRecurring,
                    onOpenSchedule = onOpenSchedule,
                    onAddSchedule = onAddSchedule,
                )
            }
        }
    }
}

/** The card's own heading: what the section is, and the way out to everything it summarises. */
@Composable
private fun RecurringSectionHeader(
    showViewAll: Boolean,
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.EventAvailable,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = stringResource(R.string.home_recurring_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (showViewAll) {
            TextButton(onClick = onViewAll) {
                Text(
                    text = stringResource(R.string.home_recurring_view_all),
                    style = MaterialTheme.typography.labelLarge,
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * The loaded section: a notice if this visit's engine run failed, then the three groups.
 *
 * A group with nothing in it is *said* rather than left out for the two groups the household is
 * waiting on - "Nothing due today" is an answer, and an absent heading is not - while overdue is
 * simply omitted, because there is nothing useful to say about no overdue bills. A household whose
 * schedules are all paused, settled or stopped therefore reads two true sentences rather than a page
 * that looks like a fresh install; only a household with no schedules at all gets the invitation.
 */
@Composable
private fun ReadySection(
    state: HomeRecurringUiState.Ready,
    onOpenRecurring: () -> Unit,
    onOpenSchedule: (Long) -> Unit,
    onAddSchedule: () -> Unit,
) {
    if (state.isFreshHousehold) {
        FreshHouseholdInvitation(onAddSchedule = onAddSchedule)
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (state.generationFailed) {
            ActionErrorStrip(
                message = stringResource(R.string.recurring_generation_failed),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        if (state.overdue.isNotEmpty()) {
            RecurringGroup(
                groupState = OccurrenceDisplayState.OVERDUE,
                header = stringResource(
                    R.string.home_recurring_group_overdue,
                    state.overdue.size,
                ),
                items = state.overdue,
                onOpenRecurring = onOpenRecurring,
                onOpenSchedule = onOpenSchedule,
            )
        }

        if (state.dueToday.isNotEmpty()) {
            RecurringGroup(
                groupState = OccurrenceDisplayState.DUE,
                header = stringResource(
                    R.string.home_recurring_group_due_today,
                    state.dueToday.size,
                ),
                items = state.dueToday,
                onOpenRecurring = onOpenRecurring,
                onOpenSchedule = onOpenSchedule,
            )
        } else {
            RecurringEmptyNote(text = stringResource(R.string.home_recurring_nothing_due_today))
        }

        if (state.upcoming.isNotEmpty()) {
            RecurringGroup(
                groupState = OccurrenceDisplayState.UPCOMING,
                // The count is how many are coming in all, not how many are drawn: the preview stops
                // at five rows, and a heading that counted the drawn rows would understate the
                // household's own month.
                header = stringResource(
                    R.string.home_recurring_group_upcoming,
                    state.upcomingTotal,
                ),
                items = state.upcoming,
                onOpenRecurring = onOpenRecurring,
                onOpenSchedule = onOpenSchedule,
            )
        } else {
            RecurringEmptyNote(text = stringResource(R.string.home_recurring_no_upcoming))
        }
    }
}

/** One group: its tinted, tappable heading, then its dates. */
@Composable
private fun RecurringGroup(
    groupState: OccurrenceDisplayState,
    header: String,
    items: List<HomeRecurringItem>,
    onOpenRecurring: () -> Unit,
    onOpenSchedule: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        RecurringGroupHeader(
            label = header,
            tone = toneFor(groupState),
            onClick = onOpenRecurring,
        )

        items.forEach { item ->
            HomeRecurringRow(item = item, onClick = { onOpenSchedule(item.scheduleId) })
        }
    }
}

/**
 * A group's heading bar.
 *
 * The whole bar is the tap target and it leads to the recurring tab, where the same dates are listed
 * with a filter over them - the heading says "*this* many", and the place to deal with them all is the
 * list. The chevron is drawn inside it for the same reason a row has one: the bar looks like a way in.
 */
@Composable
private fun RecurringGroupHeader(
    label: String,
    tone: GroupTone,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = tone.container,
        contentColor = tone.content,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * One date: what it is, how it comes round, how much was expected and where it stands.
 *
 * The mark, the name and the rule are the same three things the recurring tab's own rows lead with, so
 * a schedule reads the same in both places. The figure is the *occurrence's* stored amount, never the
 * schedule's current one: an occurrence records what was expected on the day it fell due, and an edit
 * since then must not rewrite a date the household is looking at.
 *
 * @param item the date, already read against one known today.
 * @param onClick opens the schedule the date belongs to.
 */
@Composable
private fun HomeRecurringRow(
    item: HomeRecurringItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryMark(
            iconKey = item.categoryIconKey,
            categoryName = item.categoryName,
            type = item.categoryType,
            size = 40.dp,
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = item.scheduleName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = recurringRuleLine(item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = CurrencyFormatter.formatInr(item.amountPaise),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            RecurringTiming(item = item)
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * How far off the date is, in the words that fit its group.
 *
 * Overdue and due-today dates carry a chip, because those are the two readings that want attention
 * and they are the two whose words matter ("3 days overdue" says more than "Overdue"); an upcoming
 * date is drawn as plain text, because a row of amber chips would make a month of ordinary bills look
 * like a month of problems. Both are the same sentence the schedule's own details screen prints, so
 * "3 days overdue" cannot mean one thing there and another here.
 */
@Composable
private fun RecurringTiming(item: HomeRecurringItem, modifier: Modifier = Modifier) {
    val wholeDays = abs(item.daysFromToday).toInt()

    when (item.state) {
        OccurrenceDisplayState.OVERDUE -> OccurrenceStateChip(
            state = OccurrenceDisplayState.OVERDUE,
            label = pluralStringResource(R.plurals.recurring_overdue_by_days, wholeDays, wholeDays),
            modifier = modifier,
        )

        OccurrenceDisplayState.DUE -> OccurrenceStateChip(
            state = OccurrenceDisplayState.DUE,
            modifier = modifier,
        )

        else -> Text(
            text = pluralStringResource(
                R.plurals.recurring_due_in_days,
                wholeDays,
                wholeDays,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    }
}

/**
 * The rule a date came from, with the day it falls on: "Monthly (on 5th)", "Every 2 weeks (on
 * Tuesday)", "Yearly (on 5 Feb)", and plain "Daily" for the one rule whose every date is a day.
 *
 * The day is the **occurrence's own**, not the schedule's start date, so the row describes the date it
 * is actually next to: a monthly bill pinned to the 31st that has been clamped to 28 February reads
 * "Monthly (on 28th)" for that date, which is the day the household has to find the money.
 *
 * A date can outlive the schedule that made it. Pausing or stopping a schedule stops it generating more
 * dates, but the dates already generated stay payable - the details screen keeps offering to record
 * them - so they are still drawn here, and the row names the schedule's state: "Monthly (on 5th) ·
 * Paused". Without that the date would look like a fault in the dashboard; with it, it reads as what it
 * is, the last bill of a schedule the household switched off. An active schedule says nothing extra,
 * because that is the ordinary case and a word on every row would be noise.
 *
 * Resolved in the composable rather than carried in the state because it is a translation, not a fact:
 * the state holds the frequency, the interval and the schedule's state, which are what the database
 * stores, and the wording belongs where `stringResource` can reach it.
 */
@Composable
private fun recurringRuleLine(item: HomeRecurringItem): String {
    val frequency = frequencySummary(item.frequency, item.intervalCount)

    val anchor = when (item.frequency) {
        RecurrenceFrequency.DAILY -> null
        RecurrenceFrequency.WEEKLY -> DateFormatter.formatWeekday(item.dueDate)
        RecurrenceFrequency.MONTHLY -> EnglishOrdinal.format(item.dueDate.dayOfMonth)
        RecurrenceFrequency.YEARLY -> "${EnglishOrdinal.format(item.dueDate.dayOfMonth)} " +
            DateFormatter.formatShortMonth(item.dueDate)
    }

    val rule = if (anchor == null) {
        frequency
    } else {
        stringResource(R.string.home_recurring_rule_with_anchor, frequency, anchor)
    }

    return if (item.scheduleStatus == RecurringScheduleStatus.ACTIVE) {
        rule
    } else {
        stringResource(
            R.string.home_recurring_rule_with_status,
            rule,
            stringResource(item.scheduleStatus.labelRes()),
        )
    }
}

/**
 * The invitation a household with no schedules at all gets.
 *
 * Deliberately short and free of sample figures: there is nothing to show, and inventing a rent bill
 * to fill the space would be showing a household money that is not theirs. The button goes to the same
 * form the recurring tab's own empty state opens, so the first schedule is one tap away from either.
 */
@Composable
private fun FreshHouseholdInvitation(onAddSchedule: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.home_recurring_empty_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.home_recurring_empty_message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = onAddSchedule,
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
        ) {
            Text(text = stringResource(R.string.recurring_empty_action))
        }
    }
}

/**
 * A failed read, said plainly.
 *
 * It is *not* dressed as "nothing is due": a household whose schedules could not be read still has
 * them, and the sentence says the section could not be loaded rather than that it is empty. The rest
 * of the dashboard keeps drawing, which is the whole reason the section's state is carried inside
 * [HomeUiState] instead of replacing it.
 */
@Composable
private fun FailedSection(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.home_recurring_error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = onRetry,
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
        ) {
            Text(text = stringResource(R.string.recurring_error_retry))
        }
    }
}

/** The two resolved colours a group's heading is drawn in. */
private data class GroupTone(val container: Color, val content: Color)

/**
 * The heading colours for one reading.
 *
 * The same three containers the occurrence chips use - the error red for late, the utility amber for
 * due today, the secondary green for what is simply ahead - so a household meets the same colours here
 * as on the schedule's own screens, and the words are what carry the meaning in both places.
 */
@Composable
private fun toneFor(state: OccurrenceDisplayState): GroupTone = when (state) {
    OccurrenceDisplayState.OVERDUE -> GroupTone(
        container = MaterialTheme.colorScheme.errorContainer,
        content = MaterialTheme.colorScheme.onErrorContainer,
    )

    OccurrenceDisplayState.DUE -> GroupTone(
        container = UtilityContainer,
        content = OnUtilityContainer,
    )

    else -> GroupTone(
        container = MaterialTheme.colorScheme.secondaryContainer,
        content = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}
