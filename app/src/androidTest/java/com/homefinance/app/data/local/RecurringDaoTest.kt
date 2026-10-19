package com.homefinance.app.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.homefinance.app.data.local.dao.CategoryDao
import com.homefinance.app.data.local.dao.RecurringDao
import com.homefinance.app.data.local.dao.TransactionDao
import com.homefinance.app.data.model.RecurrenceFrequency
import com.homefinance.app.data.model.RecurringOccurrenceStatus
import com.homefinance.app.data.model.RecurringScheduleStatus
import com.homefinance.app.data.model.TransactionType
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The recurring tables against a **real** SQLite database: the unique index, the three foreign keys,
 * the conditional updates, the read ordering and the `Flow`s Room generates.
 *
 * These are the guarantees a fake cannot make. `RecurringRepositoryTest` runs the repository's *rules* in
 * milliseconds, but "two occurrences of one schedule on the same day are impossible" is not a rule the
 * repository states - it is a constraint the schema enforces, and the only way to show it holds is to ask
 * SQLite to try to break it.
 */
@RunWith(AndroidJUnit4::class)
class RecurringDaoTest {

    private lateinit var database: HomeFinanceDatabase
    private lateinit var recurringDao: RecurringDao
    private lateinit var categoryDao: CategoryDao
    private lateinit var transactionDao: TransactionDao

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        recurringDao = database.recurringDao()
        categoryDao = database.categoryDao()
        transactionDao = database.transactionDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ------------------------------------------------------------- duplicates

    @Test
    fun aScheduleCannotHaveTwoOccurrencesOnTheSameDay() {
        runBlocking {
            val scheduleId = insertSchedule()

            recurringDao.insertOccurrences(listOf(occurrence(scheduleId, dueDate = testDate)))

            // Written past the DAO on purpose. The DAO's own `IGNORE` strategy turns a duplicate into a
            // no-op, which is the right behaviour for a repeated generation but hides the constraint
            // itself - so the index is asked directly, and it refuses.
            assertConstraintViolation {
                database.openHelper.writableDatabase.execSQL(
                    "INSERT INTO recurring_occurrences " +
                        "(schedule_id, due_date, amount_paise, status, transaction_id, created_at, updated_at) " +
                        "VALUES ($scheduleId, ${testDate.toEpochDay()}, 100, 'PENDING', NULL, 1, 1)",
                )
            }

            assertEquals(1, occurrenceCount())
        }
    }

    @Test
    fun theSameDueDateIsAllowedForTwoDifferentSchedules() {
        runBlocking {
            val first = insertSchedule(name = "Milk")
            val second = insertSchedule(name = "Newspaper")

            recurringDao.insertOccurrences(
                listOf(
                    occurrence(first, dueDate = testDate),
                    occurrence(second, dueDate = testDate),
                ),
            )

            assertEquals(2, occurrenceCount())
        }
    }

    @Test
    fun insertOccurrencesIgnoresTheOnesAlreadyThereAndReportsWhich() {
        runBlocking {
            val scheduleId = insertSchedule()
            val fifth = occurrence(scheduleId, dueDate = LocalDate.of(2026, 10, 5))
            val sixth = occurrence(scheduleId, dueDate = LocalDate.of(2026, 11, 5))

            assertEquals(listOf(true), recurringDao.insertOccurrences(listOf(fifth)).map { it != IGNORED })

            // Rerunning the engine over a range it has partly covered is the normal case - the progress
            // marker is an optimisation, not the truth. The row that is already there is ignored, the
            // new one is written, and the caller can tell the two apart.
            val ids = recurringDao.insertOccurrences(listOf(fifth, sixth))

            assertEquals(2, ids.size)
            assertEquals(IGNORED, ids[0])
            assertTrue(ids[1] != IGNORED)
            assertEquals(2, occurrenceCount())
        }
    }

    @Test
    fun aScheduleMustBeFiledUnderACategoryThatExists() {
        runBlocking {
            assertConstraintViolation {
                recurringDao.insertSchedule(schedule(categoryId = 4_242L))
            }
        }
    }

    @Test
    fun anOccurrenceMustBelongToAScheduleThatExists() {
        runBlocking {
            assertConstraintViolation {
                recurringDao.insertOccurrences(listOf(occurrence(scheduleId = 4_242L)))
            }
        }
    }

    @Test
    fun aCategoryThatAScheduleStillUsesCannotBeDeleted() {
        runBlocking {
            val categoryId = insertCategory()
            insertSchedule(categoryId = categoryId)

            // RESTRICT, not CASCADE: a category the household turned off must not take a schedule and
            // its whole history with it.
            assertConstraintViolation {
                database.openHelper.writableDatabase.execSQL("DELETE FROM categories WHERE id = $categoryId")
            }

            assertNotNull(recurringDao.getScheduleById(1L))
        }
    }

    @Test
    fun aScheduleWithOccurrencesCannotBeDeleted() {
        runBlocking {
            val scheduleId = insertSchedule()
            recurringDao.insertOccurrences(listOf(occurrence(scheduleId)))

            // There is no delete in the app - retiring keeps the row - and the schema refuses to let one
            // be added later by accident, because the occurrences hold the household's history.
            assertConstraintViolation {
                database.openHelper.writableDatabase
                    .execSQL("DELETE FROM recurring_schedules WHERE id = $scheduleId")
            }

            assertEquals(1, occurrenceCount())
        }
    }

    @Test
    fun deletingAPaidTransactionUnlinksItsOccurrenceWithoutChangingItsStatus() {
        runBlocking {
            val categoryId = insertCategory()
            val scheduleId = insertSchedule(categoryId = categoryId)
            val transactionId = transactionDao.insert(
                transaction(categoryId = categoryId, description = "Shop rent"),
            )
            val occurrenceId = recurringDao
                .insertOccurrences(
                    listOf(occurrence(scheduleId, status = RecurringOccurrenceStatus.PAID, transactionId = transactionId)),
                )
                .single()

            assertEquals(1, transactionDao.deleteById(transactionId))

            val stored = recurringDao.getOccurrenceById(occurrenceId)
            assertNotNull(stored)
            // SET NULL, not CASCADE: deleting an expense from the ledger must not delete the record that
            // the bill was paid. The occurrence keeps saying it was paid, and the screens say the entry is
            // gone rather than quietly re-labelling it as unpaid.
            assertNull(stored!!.transactionId)
            assertEquals(RecurringOccurrenceStatus.PAID, stored.status)
            assertEquals(1, occurrenceCount())
        }
    }

    // --------------------------------------------------------------- ordering

    @Test
    fun schedulesAreOrderedActiveFirstThenBySoonestDueDate() {
        runBlocking {
            val categoryId = insertCategory()
            val active = insertSchedule(name = "Active", categoryId = categoryId)
            insertSchedule(
                name = "Paused",
                categoryId = categoryId,
                status = RecurringScheduleStatus.PAUSED,
            )
            insertSchedule(
                name = "Stopped",
                categoryId = categoryId,
                status = RecurringScheduleStatus.RETIRED,
            )

            recurringDao.insertOccurrences(listOf(occurrence(active, dueDate = LocalDate.of(2026, 11, 5))))

            assertEquals(
                listOf("Active", "Paused", "Stopped"),
                recurringDao.observeSchedulesWithProgress().first().map { it.name },
            )
        }
    }

    @Test
    fun theProgressProjectionCarriesTheSoonestPendingDateAndNotASettledOne() {
        runBlocking {
            val scheduleId = insertSchedule()
            recurringDao.insertOccurrences(
                listOf(
                    occurrence(scheduleId, dueDate = LocalDate.of(2026, 10, 5)),
                    occurrence(
                        scheduleId,
                        dueDate = LocalDate.of(2026, 11, 5),
                        status = RecurringOccurrenceStatus.PAID,
                    ),
                    occurrence(scheduleId, dueDate = LocalDate.of(2026, 12, 5)),
                ),
            )

            val stored = recurringDao.observeScheduleWithProgress(scheduleId).first()
            assertNotNull(stored)
            // October is the earliest and it is pending; November is older than December but has been
            // paid, so it is not "next due" - it has been dealt with.
            assertEquals(LocalDate.of(2026, 10, 5), stored!!.nextDueDate)
        }
    }

    @Test
    fun aScheduleWithNothingPendingHasNoNextDueDate() {
        runBlocking {
            val scheduleId = insertSchedule()
            recurringDao.insertOccurrences(
                listOf(occurrence(scheduleId, status = RecurringOccurrenceStatus.SKIPPED)),
            )

            assertNull(recurringDao.observeScheduleWithProgress(scheduleId).first()!!.nextDueDate)
        }
    }

    @Test
    fun occurrencesAreReadNewestFirst() {
        runBlocking {
            val scheduleId = insertSchedule()
            recurringDao.insertOccurrences(
                listOf(
                    occurrence(scheduleId, dueDate = LocalDate.of(2026, 10, 5)),
                    occurrence(scheduleId, dueDate = LocalDate.of(2026, 9, 5)),
                    occurrence(scheduleId, dueDate = LocalDate.of(2026, 11, 5)),
                ),
            )

            assertEquals(
                listOf(
                    LocalDate.of(2026, 11, 5),
                    LocalDate.of(2026, 10, 5),
                    LocalDate.of(2026, 9, 5),
                ),
                recurringDao.observeOccurrencesForSchedule(scheduleId).first().map { it.dueDate },
            )
        }
    }

    @Test
    fun anOccurrenceRowCarriesItsSchedulesNameAndCategoryName() {
        runBlocking {
            val categoryId = insertCategory(name = "Rent")
            val scheduleId = insertSchedule(name = "Shop rent", categoryId = categoryId)
            recurringDao.insertOccurrences(listOf(occurrence(scheduleId)))

            val row = recurringDao.observeOccurrencesForSchedule(scheduleId).first().single()

            // Read through one join rather than three queries: the payment dialog states all three of
            // these before the household confirms anything.
            assertEquals("Shop rent", row.scheduleName)
            assertEquals("Rent", row.categoryName)
            assertEquals(1_500_000L, row.amountPaise)
        }
    }

    @Test
    fun theListProjectionCarriesTheSchedulesUnitPriceAndTheCategorysDirection() {
        runBlocking {
            val categoryId = insertCategory(name = "Milk", type = TransactionType.INCOME)
            insertSchedule(name = "Milk round", categoryId = categoryId, unit = "litre", ratePaise = 6_000L)

            val stored = recurringDao.observeSchedulesWithProgress().first().single()

            // The unit price is the schedule's own column...
            assertEquals("litre", stored.unit)
            assertEquals(6_000L, stored.ratePaise)
            // ...and the direction is *not* a column at all: it is read from the joined category, which
            // is why an income schedule cannot be drawn as an expense one.
            assertEquals(TransactionType.INCOME, stored.categoryType)
        }
    }

    @Test
    fun aScheduleWhoseHouseholdNeverGaveAUnitPriceProjectsNothingForIt() {
        runBlocking {
            insertSchedule()

            val stored = recurringDao.observeSchedulesWithProgress().first().single()

            // NULL, not a zero rate: "no unit price" and "free" are different facts, and a zero would
            // print as a price beside the schedule.
            assertNull(stored.unit)
            assertNull(stored.ratePaise)
        }
    }

    // ------------------------------------------------------------------ flows

    @Test
    fun observingASchedulesOccurrencesEmitsAgainWhenAnotherIsGenerated() {
        runBlocking {
            val scheduleId = insertSchedule()

            val before = recurringDao.observeOccurrencesForSchedule(scheduleId).first()
            assertTrue(before.isEmpty())

            recurringDao.insertOccurrences(listOf(occurrence(scheduleId)))

            val after = recurringDao.observeOccurrencesForSchedule(scheduleId).first()
            assertEquals(1, after.size)
        }
    }

    @Test
    fun observingTheListEmitsAgainWhenAScheduleIsPaused() {
        runBlocking {
            val scheduleId = insertSchedule()

            assertEquals(
                RecurringScheduleStatus.ACTIVE,
                recurringDao.observeSchedulesWithProgress().first().single().status,
            )

            recurringDao.pauseSchedule(
                id = scheduleId,
                from = RecurringScheduleStatus.ACTIVE,
                to = RecurringScheduleStatus.PAUSED,
                now = TEST_INSTANT,
            )

            assertEquals(
                RecurringScheduleStatus.PAUSED,
                recurringDao.observeSchedulesWithProgress().first().single().status,
            )
        }
    }

    // ----------------------------------------------------- conditional updates

    @Test
    fun anOccurrenceIsOnlyPaidWhileItIsStillPending() {
        runBlocking {
            val categoryId = insertCategory()
            val scheduleId = insertSchedule(categoryId = categoryId)
            val transactionId = transactionDao.insert(transaction(categoryId = categoryId))
            val occurrenceId = recurringDao
                .insertOccurrences(listOf(occurrence(scheduleId)))
                .single()

            assertEquals(
                1,
                recurringDao.markOccurrencePaid(
                    id = occurrenceId,
                    pending = RecurringOccurrenceStatus.PENDING,
                    paid = RecurringOccurrenceStatus.PAID,
                    transactionId = transactionId,
                    now = TEST_INSTANT,
                ),
            )

            // The second attempt changes nothing, which is what tells the repository to roll its own
            // expense back instead of leaving a second payment in the ledger.
            assertEquals(
                0,
                recurringDao.markOccurrencePaid(
                    id = occurrenceId,
                    pending = RecurringOccurrenceStatus.PENDING,
                    paid = RecurringOccurrenceStatus.PAID,
                    transactionId = transactionId,
                    now = TEST_INSTANT,
                ),
            )
        }
    }

    @Test
    fun anOccurrenceIsOnlySkippedWhileItIsStillPending() {
        runBlocking {
            val scheduleId = insertSchedule()
            val occurrenceId = recurringDao
                .insertOccurrences(
                    listOf(occurrence(scheduleId, status = RecurringOccurrenceStatus.PAID)),
                )
                .single()

            // A paid bill cannot be turned into a skipped one: the payment is a fact that happened.
            assertEquals(
                0,
                recurringDao.markOccurrenceSkipped(
                    id = occurrenceId,
                    pending = RecurringOccurrenceStatus.PENDING,
                    skipped = RecurringOccurrenceStatus.SKIPPED,
                    now = TEST_INSTANT,
                ),
            )
            assertEquals(
                RecurringOccurrenceStatus.PAID,
                recurringDao.getOccurrenceById(occurrenceId)!!.status,
            )
        }
    }

    @Test
    fun pausingOnlyChangesAScheduleThatIsStillActive() {
        runBlocking {
            val scheduleId = insertSchedule()

            assertEquals(1, pause(scheduleId))
            assertEquals(0, pause(scheduleId))
            assertEquals(
                RecurringScheduleStatus.PAUSED,
                recurringDao.getScheduleById(scheduleId)!!.status,
            )
        }
    }

    @Test
    fun resumingMovesTheStatusAndTheProgressInOneStatement() {
        runBlocking {
            val scheduleId = insertSchedule()
            pause(scheduleId)

            val resumed = recurringDao.resumeSchedule(
                id = scheduleId,
                from = RecurringScheduleStatus.PAUSED,
                to = RecurringScheduleStatus.ACTIVE,
                generatedThrough = LocalDate.of(2026, 10, 19),
                now = TEST_INSTANT,
            )

            assertEquals(1, resumed)
            val stored = recurringDao.getScheduleById(scheduleId)!!
            assertEquals(RecurringScheduleStatus.ACTIVE, stored.status)
            // Both in one `UPDATE` on purpose: a schedule that came back active without its progress
            // having moved would immediately back-fill everything the pause was there to stop.
            assertEquals(LocalDate.of(2026, 10, 19), stored.generatedThrough)
        }
    }

    @Test
    fun aStoppedScheduleCannotBePausedOrResumed() {
        runBlocking {
            val scheduleId = insertSchedule()

            assertEquals(1, retire(scheduleId))
            assertEquals(0, pause(scheduleId))
            assertEquals(
                0,
                recurringDao.resumeSchedule(
                    id = scheduleId,
                    from = RecurringScheduleStatus.PAUSED,
                    to = RecurringScheduleStatus.ACTIVE,
                    generatedThrough = null,
                    now = TEST_INSTANT,
                ),
            )
            // A stop is one-way by design: restarting a finished schedule would be a new schedule, with
            // its own start date and its own history.
            assertEquals(0, retire(scheduleId))
            assertEquals(
                RecurringScheduleStatus.RETIRED,
                recurringDao.getScheduleById(scheduleId)!!.status,
            )
        }
    }

    @Test
    fun recordingProgressDoesNotTouchWhenTheHouseholdLastChangedTheSchedule() {
        runBlocking {
            val scheduleId = insertSchedule()

            recurringDao.setGeneratedThrough(scheduleId, LocalDate.of(2026, 12, 5))

            val stored = recurringDao.getScheduleById(scheduleId)!!
            assertEquals(LocalDate.of(2026, 12, 5), stored.generatedThrough)
            // The generator's own bookkeeping must not make a schedule look edited.
            assertEquals(TEST_INSTANT, stored.updatedAt)
        }
    }

    @Test
    fun theSchedulesWithAGivenStatusAreReadById() {
        runBlocking {
            val first = insertSchedule(name = "First")
            insertSchedule(name = "Second", status = RecurringScheduleStatus.PAUSED)
            val third = insertSchedule(name = "Third")

            assertEquals(
                listOf(first, third),
                recurringDao.getSchedulesWithStatus(RecurringScheduleStatus.ACTIVE).map { it.id },
            )
        }
    }

    @Test
    fun twoSchedulesMayShareAName() {
        runBlocking {
            val categoryId = insertCategory()

            insertSchedule(name = "Milk", categoryId = categoryId)
            insertSchedule(name = "Milk", categoryId = categoryId)

            // Deliberately not unique: a household may have milk morning and evening, and the two are
            // different schedules with different start dates.
            assertEquals(2, recurringDao.observeSchedulesWithProgress().first().size)
        }
    }

    // ------------------------------------------------------- the dashboard's outstanding read

    @Test
    fun theOutstandingReadReturnsEveryWaitingDateSoonestFirst() {
        runBlocking {
            val rent = insertSchedule(name = "Shop rent")
            val broadband = insertSchedule(name = "Broadband")
            recurringDao.insertOccurrences(
                listOf(
                    occurrence(scheduleId = rent, dueDate = testDate.plusDays(10)),
                    occurrence(scheduleId = broadband, dueDate = testDate.minusDays(2)),
                    occurrence(scheduleId = rent, dueDate = testDate),
                ),
            )

            val outstanding = recurringDao.observeOutstandingOccurrences().first()

            // Across schedules, oldest first: the date that has waited longest is the one the dashboard
            // draws at the top.
            assertEquals(
                listOf(testDate.minusDays(2), testDate, testDate.plusDays(10)),
                outstanding.map { it.dueDate },
            )
            assertEquals(
                listOf("Broadband", "Shop rent", "Shop rent"),
                outstanding.map { it.scheduleName },
            )
        }
    }

    @Test
    fun twoDatesOnTheSameDayAreOrderedByScheduleName() {
        runBlocking {
            val zeta = insertSchedule(name = "Zeta")
            val alpha = insertSchedule(name = "Alpha")
            recurringDao.insertOccurrences(
                listOf(
                    occurrence(scheduleId = zeta, dueDate = testDate),
                    occurrence(scheduleId = alpha, dueDate = testDate),
                ),
            )

            // The tie-break is what stops two reads of unchanged data shuffling two rows on one day.
            assertEquals(
                listOf("Alpha", "Zeta"),
                recurringDao.observeOutstandingOccurrences().first().map { it.scheduleName },
            )
        }
    }

    @Test
    fun aDateThatIsDoneWithIsNotOutstanding() {
        runBlocking {
            val categoryId = insertCategory()
            val scheduleId = insertSchedule(categoryId = categoryId)
            val transactionId = transactionDao.insert(
                transaction(categoryId = categoryId, description = "Shop rent"),
            )
            recurringDao.insertOccurrences(
                listOf(
                    occurrence(scheduleId = scheduleId, dueDate = testDate),
                    occurrence(
                        scheduleId = scheduleId,
                        dueDate = testDate.plusDays(1),
                        status = RecurringOccurrenceStatus.PAID,
                        transactionId = transactionId,
                    ),
                    occurrence(
                        scheduleId = scheduleId,
                        dueDate = testDate.plusDays(2),
                        status = RecurringOccurrenceStatus.SKIPPED,
                    ),
                ),
            )

            val outstanding = recurringDao.observeOutstandingOccurrences().first()

            // A settled date is not work, and it is not counted twice either: it is simply not in a
            // reading that is about what still wants doing.
            assertEquals(listOf(testDate), outstanding.map { it.dueDate })
            assertEquals(3, occurrenceCount())
        }
    }

    @Test
    fun aPausedOrStoppedSchedulesWaitingDatesAreStillOutstanding() {
        runBlocking {
            val running = insertSchedule(name = "Running")
            val paused = insertSchedule(name = "Paused")
            val stopped = insertSchedule(name = "Stopped")
            recurringDao.insertOccurrences(
                listOf(
                    occurrence(scheduleId = running, dueDate = testDate),
                    occurrence(scheduleId = paused, dueDate = testDate),
                    occurrence(scheduleId = stopped, dueDate = testDate),
                ),
            )
            assertEquals(1, pause(paused))
            assertEquals(1, retire(stopped))

            val outstanding = recurringDao.observeOutstandingOccurrences().first()

            // All three, soonest first: pausing and stopping stop the engine making more dates, but the
            // dates already generated stay payable, so a read of what can still be acted on must not
            // drop them. The state travels with each row so the dashboard can say which is which.
            assertEquals(listOf("Paused", "Running", "Stopped"), outstanding.map { it.scheduleName })
            assertEquals(
                listOf(
                    RecurringScheduleStatus.PAUSED,
                    RecurringScheduleStatus.ACTIVE,
                    RecurringScheduleStatus.RETIRED,
                ),
                outstanding.map { it.scheduleStatus },
            )
        }
    }

    @Test
    fun aRowCarriesItsOwnAmountAndTheSchedulesCategoryRatherThanTheSchedulesAmount() {
        runBlocking {
            val salaryCategory = insertCategory(name = "Salary", type = TransactionType.INCOME)
            val salary = insertSchedule(name = "Salary", categoryId = salaryCategory)
            recurringDao.insertOccurrences(
                listOf(
                    occurrence(
                        scheduleId = salary,
                        dueDate = testDate,
                        amountPaise = 4_500_000L,
                    ),
                ),
            )

            val row = recurringDao.observeOutstandingOccurrences().first().single()

            // What was expected on the day, not what the schedule says today: an edit states what
            // happens next time, and cannot rewrite a date already on screen.
            assertEquals(4_500_000L, row.amountPaise)
            assertEquals(salary, row.scheduleId)
            assertEquals(RecurringScheduleStatus.ACTIVE, row.scheduleStatus)
            assertEquals(TransactionType.INCOME, row.categoryType)
            assertEquals("Salary", row.categoryName)
            assertEquals(RecurrenceFrequency.MONTHLY, row.frequency)
            assertEquals(1, row.intervalCount)
            assertEquals(RecurringOccurrenceStatus.PENDING, row.status)
            assertNull(row.categoryIconKey)
        }
    }

    @Test
    fun theScheduleCountCountsEveryScheduleWhateverItsState() {
        runBlocking {
            insertSchedule(name = "Running")
            val stopped = insertSchedule(name = "Stopped")
            assertEquals(1, retire(stopped))

            // Two schedules, no waiting dates: "they have schedules, none of them are asking for
            // anything" is a different sentence from "they have nothing set up", and only the count
            // can tell them apart once the outstanding read has nothing to return.
            assertEquals(2, recurringDao.observeScheduleCount().first())
            assertTrue(recurringDao.observeOutstandingOccurrences().first().isEmpty())
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * How many categories this test has made.
     *
     * Category names are unique per type, so a helper that took a fixed default name would fail on the
     * second call - and several tests need two schedules. Counting instead keeps each test's intent to
     * itself.
     */
    private var categoriesCreated = 0

    private suspend fun insertSchedule(
        name: String = "Shop rent",
        categoryId: Long? = null,
        status: RecurringScheduleStatus = RecurringScheduleStatus.ACTIVE,
        unit: String? = null,
        ratePaise: Long? = null,
    ): Long = recurringDao.insertSchedule(
        schedule(
            categoryId = categoryId ?: insertCategory(),
            name = name,
            status = status,
            unit = unit,
            ratePaise = ratePaise,
        ),
    )

    private suspend fun insertCategory(
        name: String = "Category ${++categoriesCreated}",
        type: TransactionType = TransactionType.EXPENSE,
    ): Long = categoryDao.insert(category(name = name, type = type))

    private suspend fun pause(id: Long): Int = recurringDao.pauseSchedule(
        id = id,
        from = RecurringScheduleStatus.ACTIVE,
        to = RecurringScheduleStatus.PAUSED,
        now = TEST_INSTANT,
    )

    private suspend fun retire(id: Long): Int = recurringDao.retireSchedule(
        id = id,
        active = RecurringScheduleStatus.ACTIVE,
        paused = RecurringScheduleStatus.PAUSED,
        to = RecurringScheduleStatus.RETIRED,
        now = TEST_INSTANT,
    )

    private suspend fun occurrenceCount(): Int =
        database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM recurring_occurrences")
            .use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }

    private companion object {

        val testDate: LocalDate = LocalDate.of(2026, 10, 5)

        /** The row id `OnConflictStrategy.IGNORE` reports for a row it did not insert. */
        const val IGNORED = -1L
    }
}
