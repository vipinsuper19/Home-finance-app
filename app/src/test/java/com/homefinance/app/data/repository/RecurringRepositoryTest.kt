package com.homefinance.app.data.repository

import com.homefinance.app.data.local.TransactionRunner
import com.homefinance.app.data.local.entity.CategoryEntity
import com.homefinance.app.data.local.entity.RecurringOccurrenceEntity
import com.homefinance.app.data.local.entity.RecurringScheduleEntity
import com.homefinance.app.data.model.RecurrenceFrequency
import com.homefinance.app.data.model.RecurringOccurrenceStatus
import com.homefinance.app.data.model.RecurringScheduleStatus
import com.homefinance.app.data.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recurring repository's rules: what it refuses to store, what an edit does and does not rewrite,
 * and - the part that matters most - what recording a payment actually writes.
 *
 * These run against the in-memory fakes. The behaviours that only a real database can demonstrate -
 * that the unique index refuses a second occurrence for the same date, that two payments racing
 * produce exactly one expense, and that a failure part-way through a payment is rolled back - are
 * asserted against a real in-memory Room database in `RecurringRepositoryDatabaseIntegrationTest`.
 */
class RecurringRepositoryTest {

    private val now: Instant = Instant.parse("2026-10-15T09:00:00Z")
    private val today: LocalDate = LocalDate.of(2026, 10, 15)

    // ------------------------------------------------------------ creating

    @Test
    fun creatingAScheduleStoresTheNormalisedNameAndGeneratesItsOccurrences() = runBlocking {
        val fixture = Fixture()

        val id = fixture.repository.createSchedule(
            name = "  Shop   rent ",
            categoryId = RENT_ID,
            amountPaise = 1_500_000L,
            frequency = MONTHLY,
            intervalCount = 1,
            startDate = date(2026, 10, 5),
            endDate = null,
            today = today,
            now = now,
        )

        val stored = fixture.recurringDao.storedSchedules.single()
        assertEquals(id, stored.id)
        assertEquals("Shop rent", stored.name)
        assertEquals(RecurringScheduleStatus.ACTIVE, stored.status)
        assertEquals(date(2027, 1, 5), stored.generatedThrough)
        assertEquals(now, stored.createdAt)
        assertTrue("creating a schedule must generate its occurrences", fixture.recurringDao.storedOccurrences.isNotEmpty())
    }

    @Test
    fun creatingAScheduleWritesNoTransactionAtAll() = runBlocking {
        val fixture = Fixture()

        fixture.repository.createSchedule(
            name = "Shop rent",
            categoryId = RENT_ID,
            amountPaise = 1_500_000L,
            frequency = MONTHLY,
            intervalCount = 1,
            startDate = date(2026, 10, 5),
            endDate = null,
            today = today,
            now = now,
        )

        assertTrue(fixture.recurringDao.storedOccurrences.isNotEmpty())
        assertEquals(emptyList<Any>(), fixture.transactionDao.inserted)
    }

    @Test
    fun aDescriptionIsTrimmedAndABlankOneIsStoredAsNothing() = runBlocking {
        val fixture = Fixture()

        fixture.repository.createSchedule(
            name = "Shop rent",
            categoryId = RENT_ID,
            amountPaise = 1_500_000L,
            frequency = MONTHLY,
            intervalCount = 1,
            startDate = date(2026, 10, 5),
            endDate = null,
            today = today,
            description = "   Ground   floor   ",
            now = now,
        )
        fixture.repository.createSchedule(
            name = "Milk",
            categoryId = RENT_ID,
            amountPaise = 100L,
            frequency = MONTHLY,
            intervalCount = 1,
            startDate = date(2026, 10, 5),
            endDate = null,
            today = today,
            description = "   ",
            now = now,
        )

        val stored = fixture.recurringDao.storedSchedules.associateBy { it.name }
        // Trimmed like a name, but inner runs of whitespace are the household's own formatting.
        assertEquals("Ground   floor", stored.getValue("Shop rent").description)
        // Blank is "not given", not an empty line in the list.
        assertNull(stored.getValue("Milk").description)
    }

    @Test
    fun aBlankNameIsRefusedAndNothingIsWritten() = runBlocking {        val fixture = Fixture()

        assertFails {
            fixture.repository.createSchedule(
                name = "   ",
                categoryId = RENT_ID,
                amountPaise = 1_500_000L,
                frequency = MONTHLY,
                intervalCount = 1,
                startDate = today,
                endDate = null,
                today = today,
                now = now,
            )
        }

        assertEquals(emptyList<RecurringScheduleEntity>(), fixture.recurringDao.storedSchedules)
    }

    @Test
    fun anAmountThatIsNotPositiveIsRefused() = runBlocking {
        val fixture = Fixture()

        assertFails {
            fixture.repository.createSchedule(
                name = "Rent",
                categoryId = RENT_ID,
                amountPaise = 0L,
                frequency = MONTHLY,
                intervalCount = 1,
                startDate = today,
                endDate = null,
                today = today,
                now = now,
            )
        }
        assertFails {
            fixture.repository.createSchedule(
                name = "Rent",
                categoryId = RENT_ID,
                amountPaise = -1L,
                frequency = MONTHLY,
                intervalCount = 1,
                startDate = today,
                endDate = null,
                today = today,
                now = now,
            )
        }

        assertEquals(emptyList<RecurringScheduleEntity>(), fixture.recurringDao.storedSchedules)
    }

    @Test
    fun anIntervalOutsideTheSupportedRangeIsRefused() = runBlocking {
        val fixture = Fixture()

        assertFails {
            fixture.repository.createSchedule(
                name = "Rent",
                categoryId = RENT_ID,
                amountPaise = 100L,
                frequency = MONTHLY,
                intervalCount = 0,
                startDate = today,
                endDate = null,
                today = today,
                now = now,
            )
        }
        assertFails {
            fixture.repository.createSchedule(
                name = "Rent",
                categoryId = RENT_ID,
                amountPaise = 100L,
                frequency = MONTHLY,
                intervalCount = 1_001,
                startDate = today,
                endDate = null,
                today = today,
                now = now,
            )
        }

        assertEquals(emptyList<RecurringScheduleEntity>(), fixture.recurringDao.storedSchedules)
    }

    @Test
    fun anEndDateBeforeTheStartDateIsRefused() = runBlocking {
        val fixture = Fixture()

        assertFails {
            fixture.repository.createSchedule(
                name = "Rent",
                categoryId = RENT_ID,
                amountPaise = 100L,
                frequency = MONTHLY,
                intervalCount = 1,
                startDate = date(2026, 10, 5),
                endDate = date(2026, 10, 4),
                today = today,
                now = now,
            )
        }

        assertEquals(emptyList<RecurringScheduleEntity>(), fixture.recurringDao.storedSchedules)
    }

    @Test
    fun aCategoryThatDoesNotExistIsRefused() = runBlocking {
        val fixture = Fixture()

        assertFails {
            fixture.repository.createSchedule(
                name = "Rent",
                categoryId = 999L,
                amountPaise = 100L,
                frequency = MONTHLY,
                intervalCount = 1,
                startDate = today,
                endDate = null,
                today = today,
                now = now,
            )
        }
    }

    @Test
    fun anIncomeCategoryIsAcceptedAndStored() = runBlocking {
        val fixture = Fixture()

        val id = fixture.repository.createSchedule(
            name = "Salary",
            categoryId = SALARY_ID,
            amountPaise = 4_500_000L,
            frequency = MONTHLY,
            intervalCount = 1,
            startDate = today,
            endDate = null,
            today = today,
            now = now,
        )

        val stored = fixture.recurringDao.storedSchedules.single { it.id == id }
        assertEquals(SALARY_ID, stored.categoryId)
        assertEquals(4_500_000L, stored.amountPaise)
        // No transaction, exactly as for an expense schedule: creating an income plan is not income.
        assertEquals(emptyList<Any>(), fixture.transactionDao.inserted)
    }

    @Test
    fun aUnitPriceIsStoredOnTheSchedule() = runBlocking {
        val fixture = Fixture()

        val id = fixture.repository.createSchedule(
            name = "Milk",
            categoryId = RENT_ID,
            amountPaise = 6_000L,
            frequency = MONTHLY,
            intervalCount = 1,
            startDate = today,
            endDate = null,
            today = today,
            unit = "  litre  ",
            ratePaise = 6_000L,
            now = now,
        )

        val stored = fixture.recurringDao.storedSchedules.single { it.id == id }
        // Trimmed like the description, and kept separate from the expected amount.
        assertEquals("litre", stored.unit)
        assertEquals(6_000L, stored.ratePaise)
        assertEquals(6_000L, stored.amountPaise)
    }

    @Test
    fun aUnitWithoutARateIsRefused() = runBlocking {
        val fixture = Fixture()

        assertFails {
            fixture.repository.createSchedule(
                name = "Milk",
                categoryId = RENT_ID,
                amountPaise = 6_000L,
                frequency = MONTHLY,
                intervalCount = 1,
                startDate = today,
                endDate = null,
                today = today,
                unit = "litre",
                now = now,
            )
        }
        assertEquals(emptyList<Any>(), fixture.recurringDao.insertedSchedules)
    }

    @Test
    fun aRateWithoutAUnitIsRefused() = runBlocking {
        val fixture = Fixture()

        assertFails {
            fixture.repository.createSchedule(
                name = "Milk",
                categoryId = RENT_ID,
                amountPaise = 6_000L,
                frequency = MONTHLY,
                intervalCount = 1,
                startDate = today,
                endDate = null,
                today = today,
                ratePaise = 6_000L,
                now = now,
            )
        }
        assertEquals(emptyList<Any>(), fixture.recurringDao.insertedSchedules)
    }

    @Test
    fun aBlankUnitIsTreatedAsNoUnitPriceAtAll() = runBlocking {
        val fixture = Fixture()

        // A unit that is only spaces has been normalised to `null` before validation, so "no unit with
        // no rate" is a schedule without a unit price rather than a half-given pair.
        val id = fixture.repository.createSchedule(
            name = "Milk",
            categoryId = RENT_ID,
            amountPaise = 6_000L,
            frequency = MONTHLY,
            intervalCount = 1,
            startDate = today,
            endDate = null,
            today = today,
            unit = "   ",
            now = now,
        )

        val stored = fixture.recurringDao.storedSchedules.single { it.id == id }
        assertEquals(null, stored.unit)
        assertEquals(null, stored.ratePaise)
    }

    @Test
    fun aRateThatIsNotPositiveIsRefused() = runBlocking {
        val fixture = Fixture()

        assertFails {
            fixture.repository.createSchedule(
                name = "Milk",
                categoryId = RENT_ID,
                amountPaise = 6_000L,
                frequency = MONTHLY,
                intervalCount = 1,
                startDate = today,
                endDate = null,
                today = today,
                unit = "litre",
                ratePaise = 0L,
                now = now,
            )
        }
        assertEquals(emptyList<Any>(), fixture.recurringDao.insertedSchedules)
    }

    @Test
    fun editingCanAddAndRemoveTheUnitPrice() = runBlocking {
        val fixture = Fixture(initialSchedules = listOf(schedule()))

        fixture.repository.updateSchedule(
            id = SCHEDULE_ID,
            name = "Milk",
            categoryId = RENT_ID,
            amountPaise = 6_000L,
            frequency = MONTHLY,
            intervalCount = 1,
            startDate = date(2026, 10, 5),
            endDate = null,
            today = today,
            unit = "litre",
            ratePaise = 6_000L,
            now = now,
        )

        assertEquals("litre", fixture.recurringDao.storedSchedules.single().unit)
        assertEquals(6_000L, fixture.recurringDao.storedSchedules.single().ratePaise)

        fixture.repository.updateSchedule(
            id = SCHEDULE_ID,
            name = "Milk",
            categoryId = RENT_ID,
            amountPaise = 6_000L,
            frequency = MONTHLY,
            intervalCount = 1,
            startDate = date(2026, 10, 5),
            endDate = null,
            today = today,
            unit = null,
            ratePaise = null,
            now = now,
        )

        assertEquals(null, fixture.recurringDao.storedSchedules.single().unit)
        assertEquals(null, fixture.recurringDao.storedSchedules.single().ratePaise)
    }

    @Test
    fun aRetiredCategoryCannotBeChosenForANewSchedule() = runBlocking {
        val fixture = Fixture()

        assertFails {
            fixture.repository.createSchedule(
                name = "Old rent",
                categoryId = RETIRED_ID,
                amountPaise = 100L,
                frequency = MONTHLY,
                intervalCount = 1,
                startDate = today,
                endDate = null,
                today = today,
                now = now,
            )
        }
    }

    // ------------------------------------------------------------ editing

    @Test
    fun editingKeepsTheIdentityTheCreatedStampAndTheStatus() = runBlocking {
        val fixture = Fixture(
            initialSchedules = listOf(
                schedule(id = 5L, status = RecurringScheduleStatus.PAUSED, generatedThrough = date(2026, 11, 5)),
            ),
        )

        val updated = fixture.repository.updateSchedule(
            id = 5L,
            name = "Rent revised",
            categoryId = RENT_ID,
            amountPaise = 1_600_000L,
            frequency = MONTHLY,
            intervalCount = 2,
            startDate = date(2026, 10, 5),
            endDate = date(2027, 10, 5),
            today = today,
            now = now,
        )

        assertTrue(updated)
        val stored = fixture.recurringDao.storedSchedules.single()
        assertEquals(5L, stored.id)
        assertEquals(now, stored.createdAt)
        assertEquals(now, stored.updatedAt)
        assertEquals(RecurringScheduleStatus.PAUSED, stored.status)
        assertEquals("Rent revised", stored.name)
        assertEquals(2, stored.intervalCount)
        assertEquals(date(2027, 10, 5), stored.endDate)
    }

    @Test
    fun editingDoesNotRewriteTheAmountOfAnOccurrenceThatAlreadyExists() = runBlocking {
        val fixture = Fixture(
            initialSchedules = listOf(schedule(generatedThrough = date(2026, 10, 5))),
            initialOccurrences = listOf(
                occurrence(id = 1L, dueDate = date(2026, 10, 5), amountPaise = 1_400_000L),
            ),
        )

        fixture.repository.updateSchedule(
            id = SCHEDULE_ID,
            name = "Shop rent",
            categoryId = RENT_ID,
            amountPaise = 1_900_000L,
            frequency = MONTHLY,
            intervalCount = 1,
            startDate = date(2026, 10, 5),
            endDate = null,
            today = today,
            now = now,
        )

        val untouched = fixture.recurringDao.storedOccurrences.single { it.dueDate == date(2026, 10, 5) }
        assertEquals(1_400_000L, untouched.amountPaise)
    }

    @Test
    fun editingKeepsTheCategoryTheScheduleIsAlreadyFiledUnderEvenIfItWasRetired() = runBlocking {
        val fixture = Fixture(initialSchedules = listOf(schedule(categoryId = RETIRED_ID)))

        val updated = fixture.repository.updateSchedule(
            id = SCHEDULE_ID,
            name = "Old rent",
            categoryId = RETIRED_ID,
            amountPaise = 100L,
            frequency = MONTHLY,
            intervalCount = 1,
            startDate = date(2026, 10, 5),
            endDate = null,
            today = today,
            now = now,
        )

        assertTrue(updated)
        assertEquals(RETIRED_ID, fixture.recurringDao.storedSchedules.single().categoryId)
    }

    @Test
    fun editingRewindsTheProgressOnlyWhenTheStartDateMovesEarlier() = runBlocking {
        val earlier = Fixture(
            initialSchedules = listOf(schedule(startDate = date(2026, 10, 5), generatedThrough = date(2026, 12, 5))),
        )

        earlier.repository.updateSchedule(
            id = SCHEDULE_ID,
            name = "Shop rent",
            categoryId = RENT_ID,
            amountPaise = 1_500_000L,
            frequency = MONTHLY,
            intervalCount = 1,
            startDate = date(2026, 6, 5),
            endDate = null,
            today = today,
            now = now,
        )

        assertEquals(date(2026, 6, 4), earlier.recurringDao.updatedSchedules.single().generatedThrough)
        assertEquals(
            listOf(
                date(2026, 6, 5),
                date(2026, 7, 5),
                date(2026, 8, 5),
                date(2026, 9, 5),
                date(2026, 10, 5),
                date(2026, 11, 5),
                date(2026, 12, 5),
                date(2027, 1, 5),
            ),
            earlier.recurringDao.storedOccurrences.map { it.dueDate }.sorted(),
        )

        val unchanged = Fixture(
            initialSchedules = listOf(schedule(startDate = date(2026, 10, 5), generatedThrough = date(2026, 12, 5))),
        )

        unchanged.repository.updateSchedule(
            id = SCHEDULE_ID,
            name = "Shop rent",
            categoryId = RENT_ID,
            amountPaise = 1_500_000L,
            frequency = MONTHLY,
            intervalCount = 1,
            startDate = date(2026, 10, 5),
            endDate = null,
            today = today,
            now = now,
        )

        // The progress was left alone, so nothing before it is filled in.
        assertEquals(
            listOf(date(2027, 1, 5)),
            unchanged.recurringDao.storedOccurrences.map { it.dueDate }.sorted(),
        )
    }

    @Test
    fun editingAScheduleThatIsGoneChangesNothing() = runBlocking {
        val fixture = Fixture()

        val updated = fixture.repository.updateSchedule(
            id = 404L,
            name = "Shop rent",
            categoryId = RENT_ID,
            amountPaise = 1_500_000L,
            frequency = MONTHLY,
            intervalCount = 1,
            startDate = today,
            endDate = null,
            today = today,
            now = now,
        )

        assertFalse(updated)
        assertEquals(emptyList<RecurringScheduleEntity>(), fixture.recurringDao.storedSchedules)
        assertEquals(emptyList<RecurringScheduleEntity>(), fixture.recurringDao.updatedSchedules)
    }

    // ------------------------------------------------- pause, resume, retire

    @Test
    fun pausingStopsGenerationAndKeepsEverythingAlreadyGenerated() = runBlocking {
        val fixture = Fixture(
            initialSchedules = listOf(schedule(generatedThrough = date(2026, 10, 5))),
            initialOccurrences = listOf(occurrence(id = 1L, dueDate = date(2026, 10, 5))),
        )

        assertTrue(fixture.repository.pause(SCHEDULE_ID, now))
        assertEquals(RecurringScheduleStatus.PAUSED, fixture.recurringDao.storedSchedules.single().status)

        val created = fixture.repository.generateDueOccurrences(today, now)

        assertEquals(0, created)
        assertEquals(1, fixture.recurringDao.storedOccurrences.size)
        assertEquals(RecurringOccurrenceStatus.PENDING, fixture.recurringDao.storedOccurrences.single().status)
    }

    @Test
    fun pausingSomethingThatIsNotActiveReportsThatNothingChanged() = runBlocking {
        val fixture = Fixture(
            initialSchedules = listOf(schedule(status = RecurringScheduleStatus.PAUSED)),
        )

        assertFalse(fixture.repository.pause(SCHEDULE_ID, now))
        assertFalse(fixture.repository.pause(404L, now))
    }

    @Test
    fun retiringWorksFromActiveAndFromPausedAndOnlyOnce() = runBlocking {
        val active = Fixture(initialSchedules = listOf(schedule()))
        assertTrue(active.repository.retire(SCHEDULE_ID, now))
        assertEquals(RecurringScheduleStatus.RETIRED, active.recurringDao.storedSchedules.single().status)
        assertFalse(active.repository.retire(SCHEDULE_ID, now))

        val paused = Fixture(initialSchedules = listOf(schedule(status = RecurringScheduleStatus.PAUSED)))
        assertTrue(paused.repository.retire(SCHEDULE_ID, now))
        assertEquals(RecurringScheduleStatus.RETIRED, paused.recurringDao.storedSchedules.single().status)
    }

    @Test
    fun aRetiredScheduleGeneratesNothingFurther() = runBlocking {
        val fixture = Fixture(
            initialSchedules = listOf(schedule(generatedThrough = date(2026, 10, 5))),
            initialOccurrences = listOf(occurrence(id = 1L, dueDate = date(2026, 10, 5))),
        )

        fixture.repository.retire(SCHEDULE_ID, now)

        assertEquals(0, fixture.repository.generateDueOccurrences(today, now))
        assertEquals(1, fixture.recurringDao.storedOccurrences.size)
    }

    @Test
    fun resumingGeneratesFromTodayAndNeverBackFillsThePausedWindow() = runBlocking {
        val fixture = Fixture(
            initialSchedules = listOf(
                schedule(
                    startDate = date(2026, 10, 5),
                    status = RecurringScheduleStatus.PAUSED,
                    generatedThrough = null,
                ),
            ),
        )

        assertTrue(fixture.repository.resume(SCHEDULE_ID, today, now))

        val stored = fixture.recurringDao.storedSchedules.single()
        assertEquals(RecurringScheduleStatus.ACTIVE, stored.status)
        // 5 October fell inside the pause, so it is not suddenly overdue on 15 October.
        assertEquals(
            listOf(date(2026, 11, 5), date(2026, 12, 5), date(2027, 1, 5)),
            fixture.recurringDao.storedOccurrences.map { it.dueDate }.sorted(),
        )
    }

    @Test
    fun resumingTwiceIsHarmlessAndCreatesNothingTwice() = runBlocking {
        val fixture = Fixture(
            initialSchedules = listOf(
                schedule(
                    startDate = date(2026, 10, 5),
                    status = RecurringScheduleStatus.PAUSED,
                    generatedThrough = null,
                ),
            ),
        )

        fixture.repository.resume(SCHEDULE_ID, today, now)
        val afterFirst = fixture.recurringDao.storedOccurrences.size

        assertFalse(fixture.repository.resume(SCHEDULE_ID, today, now))

        assertEquals(afterFirst, fixture.recurringDao.storedOccurrences.size)
    }

    @Test
    fun pausingAndResumingRepeatedlyNeverDuplicatesAnOccurrence() = runBlocking {
        val fixture = Fixture(
            initialSchedules = listOf(schedule(startDate = date(2026, 10, 5))),
        )

        repeat(3) {
            fixture.repository.pause(SCHEDULE_ID, now)
            fixture.repository.resume(SCHEDULE_ID, today, now)
        }

        val dueDates = fixture.recurringDao.storedOccurrences.map { it.dueDate }
        assertEquals(dueDates.distinct(), dueDates)
    }

    @Test
    fun generationOnlyTouchesActiveSchedules() = runBlocking {
        val fixture = Fixture(
            initialSchedules = listOf(
                schedule(id = 1L, startDate = date(2026, 10, 5)),
                schedule(id = 2L, startDate = date(2026, 10, 5), status = RecurringScheduleStatus.PAUSED),
                schedule(id = 3L, startDate = date(2026, 10, 5), status = RecurringScheduleStatus.RETIRED),
            ),
        )

        val created = fixture.repository.generateDueOccurrences(today, now)

        assertEquals(4, created)
        assertTrue(fixture.recurringDao.storedOccurrences.all { it.scheduleId == 1L })
    }

    // ------------------------------------------------------------------ skip

    @Test
    fun skippingWritesNoTransactionAndKeepsTheOccurrence() = runBlocking {
        val fixture = Fixture(
            initialSchedules = listOf(schedule()),
            initialOccurrences = listOf(occurrence(id = 1L, dueDate = date(2026, 10, 5))),
        )

        assertEquals(SkipOutcome.Skipped, fixture.repository.skipOccurrence(1L, now))

        assertEquals(RecurringOccurrenceStatus.SKIPPED, fixture.recurringDao.storedOccurrences.single().status)
        assertEquals(emptyList<Any>(), fixture.transactionDao.inserted)
    }

    @Test
    fun skippingTheSameOccurrenceTwiceReportsThatItWasAlreadySettled() = runBlocking {
        val fixture = Fixture(
            initialSchedules = listOf(schedule()),
            initialOccurrences = listOf(occurrence(id = 1L, dueDate = date(2026, 10, 5))),
        )

        fixture.repository.skipOccurrence(1L, now)

        assertEquals(SkipOutcome.AlreadySettled, fixture.repository.skipOccurrence(1L, now))
    }

    @Test
    fun skippingSomethingThatIsNotThereReportsThatItIsNotThere() = runBlocking {
        assertEquals(SkipOutcome.OccurrenceNotFound, Fixture().repository.skipOccurrence(404L, now))
    }

    @Test
    fun skippingAnAlreadyPaidOccurrenceReportsThatItWasAlreadySettled() = runBlocking {
        val fixture = Fixture(
            initialSchedules = listOf(schedule()),
            initialOccurrences = listOf(
                occurrence(
                    id = 1L,
                    dueDate = date(2026, 10, 5),
                    status = RecurringOccurrenceStatus.PAID,
                    transactionId = 42L,
                ),
            ),
        )

        assertEquals(SkipOutcome.AlreadySettled, fixture.repository.skipOccurrence(1L, now))
        assertEquals(RecurringOccurrenceStatus.PAID, fixture.recurringDao.storedOccurrences.single().status)
    }

    // ------------------------------------------------------------ paying

    @Test
    fun payingWritesTheExpenseAndLinksTheOccurrenceToIt() = runBlocking {
        val fixture = Fixture(
            initialSchedules = listOf(schedule()),
            initialOccurrences = listOf(occurrence(id = 1L, dueDate = date(2026, 10, 5), amountPaise = 1_500_000L)),
        )

        val outcome = fixture.repository.markOccurrencePaid(1L, 1_500_000L, now)

        val transactionId = (outcome as MarkPaidOutcome.Paid).transactionId
        val transaction = fixture.transactionDao.inserted.single()
        assertEquals(TransactionType.EXPENSE, transaction.type)
        assertEquals(1_500_000L, transaction.amountPaise)
        assertEquals(RENT_ID, transaction.categoryId)
        assertEquals(date(2026, 10, 5), transaction.transactionDate)
        assertEquals("Shop rent", transaction.description)
        assertEquals(now, transaction.createdAt)

        val occurrence = fixture.recurringDao.storedOccurrences.single()
        assertEquals(RecurringOccurrenceStatus.PAID, occurrence.status)
        assertEquals(transactionId, occurrence.transactionId)
        assertEquals(now, occurrence.updatedAt)
    }

    @Test
    fun payingAnIncomeSchedulesOccurrenceWritesIncome() = runBlocking {
        val fixture = Fixture(
            initialSchedules = listOf(schedule(name = "Salary", categoryId = SALARY_ID, amountPaise = 4_500_000L)),
            initialOccurrences = listOf(occurrence(id = 1L, dueDate = date(2026, 10, 1), amountPaise = 4_500_000L)),
        )

        fixture.repository.markOccurrencePaid(1L, 4_500_000L, now)

        // The direction is read from the category the schedule is filed under, not passed in and not
        // stored twice: an income schedule produces income.
        val transaction = fixture.transactionDao.inserted.single()
        assertEquals(TransactionType.INCOME, transaction.type)
        assertEquals(SALARY_ID, transaction.categoryId)
        assertEquals(4_500_000L, transaction.amountPaise)
    }

    @Test
    fun theUnitPriceIsNotCarriedIntoTheEntry() = runBlocking {
        val fixture = Fixture(
            initialSchedules = listOf(schedule(unit = "litre", ratePaise = 6_000L, amountPaise = 6_000L)),
            initialOccurrences = listOf(occurrence(id = 1L, dueDate = date(2026, 10, 5), amountPaise = 6_000L)),
        )

        fixture.repository.markOccurrencePaid(1L, 6_000L, now)

        // The plan's price for one unit is not what was bought: copying it onto the entry without the
        // quantity that made the amount would store two figures that do not multiply out.
        val transaction = fixture.transactionDao.inserted.single()
        assertEquals(6_000L, transaction.amountPaise)
        assertEquals(null, transaction.quantityThousandths)
        assertEquals(null, transaction.ratePaise)
    }

    @Test
    fun payingCanRecordADifferentAmountFromTheOneExpected() = runBlocking {
        val fixture = Fixture(
            initialSchedules = listOf(schedule()),
            initialOccurrences = listOf(occurrence(id = 1L, dueDate = date(2026, 10, 5), amountPaise = 1_500_000L)),
        )

        fixture.repository.markOccurrencePaid(1L, 1_575_000L, now)

        assertEquals(1_575_000L, fixture.transactionDao.inserted.single().amountPaise)
        assertEquals(1_500_000L, fixture.recurringDao.storedOccurrences.single().amountPaise)
    }

    @Test
    fun payingTheSameOccurrenceTwiceWritesExactlyOneExpense() = runBlocking {
        val fixture = Fixture(
            initialSchedules = listOf(schedule()),
            initialOccurrences = listOf(occurrence(id = 1L, dueDate = date(2026, 10, 5))),
        )

        fixture.repository.markOccurrencePaid(1L, 1_500_000L, now)
        val second = fixture.repository.markOccurrencePaid(1L, 1_500_000L, now)

        assertEquals(MarkPaidOutcome.AlreadySettled, second)
        assertEquals(1, fixture.transactionDao.inserted.size)
    }

    @Test
    fun payingAnImplementationThatDoesNotExistWritesNothing() = runBlocking {
        val fixture = Fixture()

        assertEquals(MarkPaidOutcome.OccurrenceNotFound, fixture.repository.markOccurrencePaid(404L, 100L, now))
        assertEquals(emptyList<Any>(), fixture.transactionDao.inserted)
    }

    @Test
    fun payingASkippedOccurrenceWritesNothing() = runBlocking {
        val fixture = Fixture(
            initialSchedules = listOf(schedule()),
            initialOccurrences = listOf(
                occurrence(id = 1L, dueDate = date(2026, 10, 5), status = RecurringOccurrenceStatus.SKIPPED),
            ),
        )

        assertEquals(MarkPaidOutcome.AlreadySettled, fixture.repository.markOccurrencePaid(1L, 100L, now))
        assertEquals(emptyList<Any>(), fixture.transactionDao.inserted)
    }

    @Test
    fun payingRefusesAnAmountThatIsNotPositiveAndLeavesTheOccurrencePending() = runBlocking {
        val fixture = Fixture(
            initialSchedules = listOf(schedule()),
            initialOccurrences = listOf(occurrence(id = 1L, dueDate = date(2026, 10, 5))),
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { fixture.repository.markOccurrencePaid(1L, 0L, now) }
        }

        assertEquals(RecurringOccurrenceStatus.PENDING, fixture.recurringDao.storedOccurrences.single().status)
        assertNull(fixture.recurringDao.storedOccurrences.single().transactionId)
        assertEquals(emptyList<Any>(), fixture.transactionDao.inserted)
    }

    @Test
    fun everyPaidOccurrenceGetsItsOwnExpense() = runBlocking {
        val fixture = Fixture(
            initialSchedules = listOf(schedule()),
            initialOccurrences = listOf(
                occurrence(id = 1L, dueDate = date(2026, 10, 5)),
                occurrence(id = 2L, dueDate = date(2026, 10, 12)),
            ),
        )

        val first = fixture.repository.markOccurrencePaid(1L, 1_500_000L, now) as MarkPaidOutcome.Paid
        val second = fixture.repository.markOccurrencePaid(2L, 1_500_000L, now) as MarkPaidOutcome.Paid

        assertEquals(2, fixture.transactionDao.inserted.size)
        assertEquals(date(2026, 10, 5), fixture.transactionDao.inserted[0].transactionDate)
        assertEquals(date(2026, 10, 12), fixture.transactionDao.inserted[1].transactionDate)
        assertTrue(first.transactionId != second.transactionId)
    }

    // -------------------------------------------------------------- fixtures

    private fun assertFails(block: suspend () -> Any?) {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { block() } }
    }

    private fun schedule(
        id: Long = SCHEDULE_ID,
        name: String = "Shop rent",
        description: String? = null,
        categoryId: Long = RENT_ID,
        amountPaise: Long = 1_500_000L,
        unit: String? = null,
        ratePaise: Long? = null,
        frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
        interval: Int = 1,
        startDate: LocalDate = date(2026, 10, 5),
        endDate: LocalDate? = null,
        status: RecurringScheduleStatus = RecurringScheduleStatus.ACTIVE,
        generatedThrough: LocalDate? = null,
    ): RecurringScheduleEntity = RecurringScheduleEntity(
        id = id,
        name = name,
        description = description,
        categoryId = categoryId,
        amountPaise = amountPaise,
        unit = unit,
        ratePaise = ratePaise,
        frequency = frequency,
        intervalCount = interval,
        startDate = startDate,
        endDate = endDate,
        status = status,
        generatedThrough = generatedThrough,
        createdAt = now,
        updatedAt = now,
    )

    private fun occurrence(
        id: Long,
        dueDate: LocalDate,
        scheduleId: Long = SCHEDULE_ID,
        amountPaise: Long = 1_500_000L,
        status: RecurringOccurrenceStatus = RecurringOccurrenceStatus.PENDING,
        transactionId: Long? = null,
    ): RecurringOccurrenceEntity = RecurringOccurrenceEntity(
        id = id,
        scheduleId = scheduleId,
        dueDate = dueDate,
        amountPaise = amountPaise,
        status = status,
        transactionId = transactionId,
        createdAt = now,
        updatedAt = now,
    )

    private fun date(year: Int, month: Int, day: Int): LocalDate = LocalDate.of(year, month, day)

    /**
     * The whole data layer over the in-memory fakes, wired the way `HomeFinanceApp` wires the real
     * one, so a test exercises the same graph production does.
     */
    private class Fixture(
        initialSchedules: List<RecurringScheduleEntity> = emptyList(),
        initialOccurrences: List<RecurringOccurrenceEntity> = emptyList(),
        categories: List<CategoryEntity> = DEFAULT_CATEGORIES,
        runner: TransactionRunner = DirectTransactionRunner,
    ) {

        private val names: Map<Long, String> = categories.associate { it.id to it.name }

        private val types: Map<Long, TransactionType> = categories.associate { it.id to it.type }

        val categoryDao = FakeCategoryDao(categories)

        val transactionDao = FakeTransactionDao(categoryNames = names)

        val recurringDao = FakeRecurringDao(
            categoryNames = names,
            initialSchedules = initialSchedules,
            initialOccurrences = initialOccurrences,
            categoryTypes = types,
        )

        private val categoryRepository = CategoryRepository(categoryDao)

        val transactions = TransactionRepository(transactionDao, categoryDao)

        val repository = RecurringRepository(
            recurringDao = recurringDao,
            transactions = transactions,
            categories = categoryRepository,
            generator = RecurrenceGenerator(recurringDao, runner),
            transactionRunner = runner,
        )
    }

    private companion object {

        const val SCHEDULE_ID = 1L
        const val RENT_ID = 10L
        const val SALARY_ID = 11L
        const val RETIRED_ID = 12L

        val MONTHLY: RecurrenceFrequency = RecurrenceFrequency.MONTHLY

        val DEFAULT_CATEGORIES: List<CategoryEntity> = listOf(
            CategoryEntity.create("Rent", TransactionType.EXPENSE).copy(id = RENT_ID),
            CategoryEntity.create("Salary", TransactionType.INCOME).copy(id = SALARY_ID),
            CategoryEntity.create("Old rent", TransactionType.EXPENSE).copy(id = RETIRED_ID, isActive = false),
        )
    }
}
