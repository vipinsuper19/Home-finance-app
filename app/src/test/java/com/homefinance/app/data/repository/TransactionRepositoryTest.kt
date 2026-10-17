package com.homefinance.app.data.repository

import com.homefinance.app.data.local.entity.CategoryEntity
import com.homefinance.app.data.local.entity.TransactionEntity
import com.homefinance.app.data.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules a transaction has to satisfy before it reaches the database.
 *
 * The DAOs are fakes, so these tests run in milliseconds and need no device. What they prove is
 * exactly the part a Room DAO cannot: the validation performed by [TransactionRepository]. The
 * schema-level guarantees (the unique index, the foreign key, query ordering) are asserted
 * separately against a real in-memory Room database in `com.homefinance.app.data.local`.
 */
class TransactionRepositoryTest {

    private val now = Instant.parse("2026-10-16T06:30:00Z")
    private val later = Instant.parse("2026-10-17T09:00:00Z")
    private val date = LocalDate.of(2026, 10, 15)

    private val groceries = CategoryEntity(
        id = 1L,
        name = "Groceries",
        type = TransactionType.EXPENSE,
        createdAt = now,
    )
    private val salary = CategoryEntity(
        id = 2L,
        name = "Salary",
        type = TransactionType.INCOME,
        createdAt = now,
    )
    private val retiredRent = CategoryEntity(
        id = 3L,
        name = "Old Rent",
        type = TransactionType.EXPENSE,
        isActive = false,
        createdAt = now,
    )

    private val categoryDao = FakeCategoryDao(listOf(groceries, salary, retiredRent))
    private val transactionDao = FakeTransactionDao(
        // The names the fake's join resolves `category_id` against, standing in for the `categories`
        // table. They mirror the entities above, because a row whose category is absent is dropped -
        // exactly as the real `INNER JOIN` would.
        categoryNames = mapOf(
            groceries.id to groceries.name,
            salary.id to salary.name,
            retiredRent.id to retiredRent.name,
        ),
    )
    private val repository = TransactionRepository(transactionDao, categoryDao)

    // ---------------------------------------------------------------- add

    @Test
    fun addStoresTheAmountAsPaise() {
        runBlocking {
            val id = repository.add(
                type = TransactionType.EXPENSE,
                amountPaise = 12_345L,
                categoryId = groceries.id,
                transactionDate = date,
                now = now,
            )

            val stored = repository.getById(id)
            assertNotNull(stored)
            assertEquals(12_345L, stored!!.amountPaise)
            assertEquals(TransactionType.EXPENSE, stored.type)
            assertEquals(groceries.id, stored.categoryId)
            assertEquals(date, stored.transactionDate)
        }
    }

    @Test
    fun addStampsBothAuditTimestamps() {
        runBlocking {
            val id = repository.add(TransactionType.EXPENSE, 500L, groceries.id, date, now = now)

            val stored = repository.getById(id)!!
            assertEquals(now, stored.createdAt)
            assertEquals(now, stored.updatedAt)
        }
    }

    @Test
    fun addTrimsADescription() {
        runBlocking {
            val id = repository.add(
                TransactionType.EXPENSE,
                500L,
                groceries.id,
                date,
                description = "  August milk  ",
                now = now,
            )

            assertEquals("August milk", repository.getById(id)!!.description)
        }
    }

    @Test
    fun addStoresABlankDescriptionAsNullRatherThanWhitespace() {
        runBlocking {
            val id = repository.add(
                TransactionType.EXPENSE,
                500L,
                groceries.id,
                date,
                description = "   ",
                now = now,
            )

            assertNull(repository.getById(id)!!.description)
        }
    }

    @Test
    fun addRejectsAZeroAmount() {
        runBlocking {
            val error = assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    repository.add(TransactionType.EXPENSE, 0L, groceries.id, date, now = now)
                }
            }

            assertTrue(error.message!!.contains("positive"))
            assertTrue(transactionDao.inserted.isEmpty())
        }
    }

    @Test
    fun addRejectsANegativeAmount() {
        runBlocking {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    repository.add(TransactionType.EXPENSE, -1L, groceries.id, date, now = now)
                }
            }

            assertTrue(transactionDao.inserted.isEmpty())
        }
    }

    @Test
    fun addRejectsACategoryThatDoesNotExist() {
        runBlocking {
            val error = assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    repository.add(TransactionType.EXPENSE, 500L, 9_999L, date, now = now)
                }
            }

            assertTrue(error.message!!.contains("No category"))
            assertTrue(transactionDao.inserted.isEmpty())
        }
    }

    @Test
    fun addRejectsAnIncomeCategoryOnAnExpense() {
        runBlocking {
            val error = assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    repository.add(TransactionType.EXPENSE, 500L, salary.id, date, now = now)
                }
            }

            assertTrue(error.message!!.contains("Salary"))
            assertTrue(transactionDao.inserted.isEmpty())
        }
    }

    @Test
    fun addRejectsAnExpenseCategoryOnAnIncome() {
        runBlocking {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    repository.add(TransactionType.INCOME, 500L, groceries.id, date, now = now)
                }
            }

            assertTrue(transactionDao.inserted.isEmpty())
        }
    }

    @Test
    fun addAcceptsAnIncomeCategoryOnAnIncome() {
        runBlocking {
            val id = repository.add(TransactionType.INCOME, 5_000_000L, salary.id, date, now = now)

            assertEquals(TransactionType.INCOME, repository.getById(id)!!.type)
        }
    }

    // ------------------------------------------------------------------ notes

    @Test
    fun addTrimsNotesAndTreatsABlankFieldAsNothingWritten() {
        runBlocking {
            val trimmed = repository.add(
                TransactionType.EXPENSE,
                500L,
                groceries.id,
                date,
                notes = "  Bought at the Sunday market  ",
                now = now,
            )
            val blank = repository.add(
                TransactionType.EXPENSE,
                500L,
                groceries.id,
                date,
                notes = "   ",
                now = now,
            )

            assertEquals("Bought at the Sunday market", repository.getById(trimmed)!!.notes)
            assertNull(repository.getById(blank)!!.notes)
        }
    }

    @Test
    fun notesAndDescriptionAreStoredAsTwoSeparateFacts() {
        runBlocking {
            val id = repository.add(
                TransactionType.EXPENSE,
                500L,
                groceries.id,
                date,
                description = "Weekly shop",
                notes = "Vegetables and fruit for the week",
                now = now,
            )

            val stored = repository.getById(id)!!
            assertEquals("Weekly shop", stored.description)
            assertEquals("Vegetables and fruit for the week", stored.notes)
        }
    }

    @Test
    fun updateTrimsNotesAsWell() {
        runBlocking {
            val id = repository.add(TransactionType.EXPENSE, 500L, groceries.id, date, now = now)

            repository.update(repository.getById(id)!!.copy(notes = "  milk  "), now = later)

            assertEquals("milk", repository.getById(id)!!.notes)
        }
    }

    // ------------------------------------------------------ quantity and rate

    @Test
    fun addStoresAQuantityAndARateThatMultiplyToTheAmount() {
        runBlocking {
            val id = repository.add(
                type = TransactionType.EXPENSE,
                amountPaise = 9_000L,
                categoryId = groceries.id,
                transactionDate = date,
                quantityThousandths = 1_500L,
                ratePaise = 6_000L,
                now = now,
            )

            val stored = repository.getById(id)!!
            assertEquals(9_000L, stored.amountPaise)
            assertEquals(1_500L, stored.quantityThousandths)
            assertEquals(6_000L, stored.ratePaise)
        }
    }

    @Test
    fun anEntryWithNoQuantityStoresNullsRatherThanZeroes() {
        runBlocking {
            val id = repository.add(TransactionType.EXPENSE, 500L, groceries.id, date, now = now)

            val stored = repository.getById(id)!!
            // A zero would claim the entry was bought at a rate; nothing was recorded, so the two
            // columns are null - which is what "a plain amount was typed" actually means.
            assertNull(stored.quantityThousandths)
            assertNull(stored.ratePaise)
        }
    }

    @Test
    fun addAcceptsAProductThatHadToBeRoundedToTheNearestPaise() {
        runBlocking {
            // 0.333 x ₹59.99 is 1997.667 paise, and the repository accepts the rounded figure the
            // form showed - because it derives it with the same function.
            val id = repository.add(
                type = TransactionType.EXPENSE,
                amountPaise = 1_998L,
                categoryId = groceries.id,
                transactionDate = date,
                quantityThousandths = 333L,
                ratePaise = 5_999L,
                now = now,
            )

            assertEquals(1_998L, repository.getById(id)!!.amountPaise)
        }
    }

    @Test
    fun addRejectsAQuantityWithNoRate() {
        runBlocking {
            val error = assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    repository.add(
                        type = TransactionType.EXPENSE,
                        amountPaise = 9_000L,
                        categoryId = groceries.id,
                        transactionDate = date,
                        quantityThousandths = 1_500L,
                        now = now,
                    )
                }
            }

            assertTrue(error.message!!.contains("together or not at all"))
            assertTrue(transactionDao.inserted.isEmpty())
        }
    }

    @Test
    fun addRejectsARateWithNoQuantity() {
        runBlocking {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    repository.add(
                        type = TransactionType.EXPENSE,
                        amountPaise = 9_000L,
                        categoryId = groceries.id,
                        transactionDate = date,
                        ratePaise = 6_000L,
                        now = now,
                    )
                }
            }

            assertTrue(transactionDao.inserted.isEmpty())
        }
    }

    @Test
    fun addRejectsAnAmountThatIsNotTheProductOfTheQuantityAndRate() {
        runBlocking {
            val error = assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    // 1.5 litres at ₹60 is ₹90, so a row claiming to be ₹91 contradicts itself.
                    repository.add(
                        type = TransactionType.EXPENSE,
                        amountPaise = 9_100L,
                        categoryId = groceries.id,
                        transactionDate = date,
                        quantityThousandths = 1_500L,
                        ratePaise = 6_000L,
                        now = now,
                    )
                }
            }

            assertTrue(error.message!!.contains("which is not"))
            assertTrue(transactionDao.inserted.isEmpty())
        }
    }

    @Test
    fun addRejectsANonPositiveQuantityOrRate() {
        runBlocking {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    repository.add(
                        type = TransactionType.EXPENSE,
                        amountPaise = 9_000L,
                        categoryId = groceries.id,
                        transactionDate = date,
                        quantityThousandths = 0L,
                        ratePaise = 6_000L,
                        now = now,
                    )
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    repository.add(
                        type = TransactionType.EXPENSE,
                        amountPaise = 9_000L,
                        categoryId = groceries.id,
                        transactionDate = date,
                        quantityThousandths = 1_500L,
                        ratePaise = 0L,
                        now = now,
                    )
                }
            }

            assertTrue(transactionDao.inserted.isEmpty())
        }
    }

    @Test
    fun addRejectsAProductThatCouldNotBeMoneyAtAll() {
        runBlocking {
            // Both figures fit their columns, and the amount is a perfectly good Long, but the two
            // multiplied together overflow - so no Long amount can agree with them.
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    repository.add(
                        type = TransactionType.EXPENSE,
                        amountPaise = 500L,
                        categoryId = groceries.id,
                        transactionDate = date,
                        quantityThousandths = Long.MAX_VALUE,
                        ratePaise = Long.MAX_VALUE,
                        now = now,
                    )
                }
            }

            assertTrue(transactionDao.inserted.isEmpty())
        }
    }

    @Test
    fun updateRejectsAQuantityAndRateThatDisagreeWithTheStoredAmount() {
        runBlocking {
            val id = repository.add(TransactionType.EXPENSE, 500L, groceries.id, date, now = now)
            val stored = repository.getById(id)!!

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    repository.update(
                        stored.copy(
                            amountPaise = 9_100L,
                            quantityThousandths = 1_500L,
                            ratePaise = 6_000L,
                        ),
                        now = later,
                    )
                }
            }
        }
    }

    @Test
    fun updateAcceptsAQuantityAndRateThatAgreeWithTheStoredAmount() {
        runBlocking {
            val id = repository.add(TransactionType.EXPENSE, 500L, groceries.id, date, now = now)
            val stored = repository.getById(id)!!

            repository.update(
                stored.copy(
                    amountPaise = 9_000L,
                    quantityThousandths = 1_500L,
                    ratePaise = 6_000L,
                ),
                now = later,
            )

            val updated = repository.getById(id)!!
            assertEquals(9_000L, updated.amountPaise)
            assertEquals(1_500L, updated.quantityThousandths)
            assertEquals(6_000L, updated.ratePaise)
        }
    }

    // ------------------------------------------------------------- update

    @Test
    fun updateReStampsUpdatedAtAndLeavesCreatedAtAlone() {
        runBlocking {
            val id = repository.add(TransactionType.EXPENSE, 500L, groceries.id, date, now = now)

            repository.update(repository.getById(id)!!, now = later)

            val stored = repository.getById(id)!!
            assertEquals(now, stored.createdAt)
            assertEquals(later, stored.updatedAt)
        }
    }

    @Test
    fun updateIgnoresWhicheverUpdatedAtTheCallerSupplied() {
        runBlocking {
            val id = repository.add(TransactionType.EXPENSE, 500L, groceries.id, date, now = now)

            repository.update(repository.getById(id)!!.copy(updatedAt = Instant.EPOCH), now = later)

            assertEquals(later, repository.getById(id)!!.updatedAt)
        }
    }

    @Test
    fun updateRejectsAZeroAmount() {
        runBlocking {
            val id = repository.add(TransactionType.EXPENSE, 500L, groceries.id, date, now = now)
            val stored = repository.getById(id)!!

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    repository.update(stored.copy(amountPaise = 0L), now = later)
                }
            }
        }
    }

    @Test
    fun updateRejectsChangingToACategoryOfTheWrongType() {
        runBlocking {
            val id = repository.add(TransactionType.EXPENSE, 500L, groceries.id, date, now = now)
            val stored = repository.getById(id)!!

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    repository.update(
                        stored.copy(type = TransactionType.INCOME, categoryId = groceries.id),
                        now = later,
                    )
                }
            }
        }
    }

    @Test
    fun updateAcceptsARetiredCategorySoHistoricalRowsStayEditable() {
        runBlocking {
            val id = repository.add(TransactionType.EXPENSE, 500L, retiredRent.id, date, now = now)

            repository.update(repository.getById(id)!!.copy(amountPaise = 750L), now = later)

            assertEquals(750L, repository.getById(id)!!.amountPaise)
        }
    }

    // ------------------------------------------------- update by identifier

    @Test
    fun updateByIdRewritesEveryFigureAnEditCanChange() {
        runBlocking {
            val id = repository.add(
                type = TransactionType.EXPENSE,
                amountPaise = 500L,
                categoryId = groceries.id,
                transactionDate = date,
                description = "Milk",
                notes = "Two bottles",
                now = now,
            )

            val rewritten = repository.update(
                id = id,
                type = TransactionType.INCOME,
                amountPaise = 90_000L,
                categoryId = salary.id,
                transactionDate = date.plusDays(3),
                description = "Salary",
                notes = "October",
                now = later,
            )

            assertTrue(rewritten)
            val stored = repository.getById(id)!!
            assertEquals(TransactionType.INCOME, stored.type)
            assertEquals(90_000L, stored.amountPaise)
            assertEquals(salary.id, stored.categoryId)
            assertEquals(date.plusDays(3), stored.transactionDate)
            assertEquals("Salary", stored.description)
            assertEquals("October", stored.notes)
        }
    }

    @Test
    fun updateByIdKeepsTheIdentifierAndTheDayItWasFirstRecorded() {
        runBlocking {
            val id = repository.add(TransactionType.EXPENSE, 500L, groceries.id, date, now = now)

            repository.update(
                id = id,
                type = TransactionType.EXPENSE,
                amountPaise = 750L,
                categoryId = groceries.id,
                transactionDate = date,
                now = later,
            )

            val stored = repository.getById(id)!!
            // The id is the row's identity, not a field an edit may hand out, and createdAt is a
            // record of when the household first wrote the entry down, not of when they last
            // touched it.
            assertEquals(id, stored.id)
            assertEquals(now, stored.createdAt)
            assertEquals(later, stored.updatedAt)
        }
    }

    @Test
    fun updateByIdAcceptsARetiredCategorySoAnEditedRowKeepsItsOwn() {
        runBlocking {
            val id = repository.add(TransactionType.EXPENSE, 500L, retiredRent.id, date, now = now)

            val rewritten = repository.update(
                id = id,
                type = TransactionType.EXPENSE,
                amountPaise = 800L,
                categoryId = retiredRent.id,
                transactionDate = date,
                now = later,
            )

            assertTrue(rewritten)
            assertEquals(retiredRent.id, repository.getById(id)!!.categoryId)
        }
    }

    @Test
    fun updateByIdReturnsFalseAndWritesNothingWhenTheTransactionHasGone() {
        runBlocking {
            val removed = repository.add(TransactionType.EXPENSE, 500L, groceries.id, date, now = now)
            val kept = repository.add(TransactionType.EXPENSE, 600L, groceries.id, date, now = now)
            repository.delete(repository.getById(removed)!!)

            val rewritten = repository.update(
                id = removed,
                type = TransactionType.EXPENSE,
                amountPaise = 999L,
                categoryId = groceries.id,
                transactionDate = date,
                now = later,
            )

            assertTrue(!rewritten)
            // The refusal must not have been resolved by inserting a replacement: the household
            // would be left with a brand new transaction and no idea where it came from.
            assertNull(repository.getById(removed))
            assertEquals(1, repository.observeAll().first().size)
            assertEquals(600L, repository.getById(kept)!!.amountPaise)
        }
    }

    @Test
    fun updateByIdRejectsAZeroAmount() {
        runBlocking {
            val id = repository.add(TransactionType.EXPENSE, 500L, groceries.id, date, now = now)

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    repository.update(
                        id = id,
                        type = TransactionType.EXPENSE,
                        amountPaise = 0L,
                        categoryId = groceries.id,
                        transactionDate = date,
                        now = later,
                    )
                }
            }
            assertEquals(500L, repository.getById(id)!!.amountPaise)
        }
    }

    @Test
    fun updateByIdRejectsACategoryOfTheWrongType() {
        runBlocking {
            val id = repository.add(TransactionType.EXPENSE, 500L, groceries.id, date, now = now)

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    repository.update(
                        id = id,
                        type = TransactionType.EXPENSE,
                        amountPaise = 500L,
                        categoryId = salary.id,
                        transactionDate = date,
                        now = later,
                    )
                }
            }
            assertEquals(groceries.id, repository.getById(id)!!.categoryId)
        }
    }

    @Test
    fun updateByIdTrimsTheDescriptionAndNotesLikeAddDoes() {
        runBlocking {
            val id = repository.add(TransactionType.EXPENSE, 500L, groceries.id, date, now = now)

            repository.update(
                id = id,
                type = TransactionType.EXPENSE,
                amountPaise = 500L,
                categoryId = groceries.id,
                transactionDate = date,
                description = "  Milk  ",
                notes = "   ",
                now = later,
            )

            val stored = repository.getById(id)!!
            assertEquals("Milk", stored.description)
            assertNull(stored.notes)
        }
    }

    @Test
    fun updateByIdStoresAQuantityAndRateWhenTheEditSwitchesThemOn() {
        runBlocking {
            val id = repository.add(TransactionType.EXPENSE, 9_000L, groceries.id, date, now = now)

            repository.update(
                id = id,
                type = TransactionType.EXPENSE,
                amountPaise = 9_000L,
                categoryId = groceries.id,
                transactionDate = date,
                quantityThousandths = 1_500L,
                ratePaise = 6_000L,
                now = later,
            )

            val stored = repository.getById(id)!!
            assertEquals(1_500L, stored.quantityThousandths)
            assertEquals(6_000L, stored.ratePaise)
        }
    }

    @Test
    fun updateByIdClearsAQuantityAndRateWhenTheEditSwitchesThemOff() {
        runBlocking {
            val id = repository.add(
                type = TransactionType.EXPENSE,
                amountPaise = 9_000L,
                categoryId = groceries.id,
                transactionDate = date,
                quantityThousandths = 1_500L,
                ratePaise = 6_000L,
                now = now,
            )

            repository.update(
                id = id,
                type = TransactionType.EXPENSE,
                amountPaise = 9_500L,
                categoryId = groceries.id,
                transactionDate = date,
                quantityThousandths = null,
                ratePaise = null,
                now = later,
            )

            val stored = repository.getById(id)!!
            // Not zeroes: a plain amount has no quantity at all, and 0 would say it bought nothing.
            assertNull(stored.quantityThousandths)
            assertNull(stored.ratePaise)
            assertEquals(9_500L, stored.amountPaise)
        }
    }

    @Test
    fun updateByIdRefusesAQuantityAndRateThatDoNotAgreeWithTheAmount() {
        runBlocking {
            val id = repository.add(TransactionType.EXPENSE, 500L, groceries.id, date, now = now)

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    repository.update(
                        id = id,
                        type = TransactionType.EXPENSE,
                        amountPaise = 500L,
                        categoryId = groceries.id,
                        transactionDate = date,
                        quantityThousandths = 1_000L,
                        ratePaise = 9_000L,
                        now = later,
                    )
                }
            }
            assertEquals(500L, repository.getById(id)!!.amountPaise)
        }
    }

    // ------------------------------------------------------------- delete

    @Test
    fun deleteRemovesOnlyTheSelectedTransaction() {
        runBlocking {
            val kept = repository.add(TransactionType.EXPENSE, 500L, groceries.id, date, now = now)
            val removed = repository.add(TransactionType.INCOME, 900L, salary.id, date, now = now)

            repository.delete(repository.getById(removed)!!)

            assertNull(repository.getById(removed))
            assertNotNull(repository.getById(kept))
        }
    }

    @Test
    fun deleteByIdRemovesTheRowAndReportsThatItDid() {
        runBlocking {
            val id = repository.add(TransactionType.EXPENSE, 500L, groceries.id, date, now = now)

            assertTrue(repository.deleteById(id))
            assertNull(repository.getById(id))
        }
    }

    @Test
    fun deleteByIdReportsFalseWhenThereWasNothingToRemove() {
        runBlocking {
            val other = repository.add(TransactionType.EXPENSE, 500L, groceries.id, date, now = now)

            // A deletion the household asked for from a screen showing a row that had already gone.
            // Reporting `true` would claim a removal that never happened.
            assertTrue(!repository.deleteById(9_999L))
            assertNotNull(repository.getById(other))
        }
    }

    @Test
    fun deleteByIdLeavesEveryOtherTransactionInPlace() {
        runBlocking {
            val first = repository.add(TransactionType.EXPENSE, 100L, groceries.id, date, now = now)
            val second = repository.add(TransactionType.EXPENSE, 200L, groceries.id, date, now = now)
            val third = repository.add(TransactionType.INCOME, 300L, salary.id, date, now = now)

            repository.deleteById(second)

            assertNotNull(repository.getById(first))
            assertNull(repository.getById(second))
            assertNotNull(repository.getById(third))
            assertEquals(2, repository.observeAll().first().size)
        }
    }

    // -------------------------------------------------------------- reads

    @Test
    fun observeAllExposesTheWholeLedger() {
        runBlocking {
            repository.add(TransactionType.EXPENSE, 500L, groceries.id, date, now = now)
            repository.add(TransactionType.INCOME, 900L, salary.id, date, now = now)

            assertEquals(2, repository.observeAll().first().size)
        }
    }

    @Test
    fun observeInDateRangeIsInclusiveOnBothEnds() {
        runBlocking {
            repository.add(TransactionType.EXPENSE, 100L, groceries.id, date.minusDays(1), now = now)
            repository.add(TransactionType.EXPENSE, 200L, groceries.id, date, now = now)
            repository.add(TransactionType.EXPENSE, 300L, groceries.id, date.plusDays(1), now = now)

            val inRange: List<TransactionEntity> =
                repository.observeInDateRange(date.minusDays(1), date).first()

            assertEquals(2, inRange.size)
        }
    }

    @Test
    fun observeByTypeInDateRangeFiltersByBoth() {
        runBlocking {
            repository.add(TransactionType.EXPENSE, 100L, groceries.id, date, now = now)
            repository.add(TransactionType.INCOME, 200L, salary.id, date, now = now)

            val expenses = repository
                .observeByTypeInDateRange(TransactionType.EXPENSE, date, date)
                .first()

            assertEquals(1, expenses.size)
            assertEquals(TransactionType.EXPENSE, expenses.single().type)
        }
    }

    @Test
    fun observeByIdWithCategoryCarriesTheWholeRowAndItsCategoryName() {
        runBlocking {
            val id = repository.add(
                type = TransactionType.EXPENSE,
                amountPaise = 9_000L,
                categoryId = groceries.id,
                transactionDate = date,
                description = "Milk",
                notes = "Two bottles",
                quantityThousandths = 1_500L,
                ratePaise = 6_000L,
                now = now,
            )

            val row = repository.observeByIdWithCategory(id).first()!!

            assertEquals(id, row.id)
            assertEquals("Groceries", row.categoryName)
            assertEquals(groceries.id, row.categoryId)
            assertEquals(9_000L, row.amountPaise)
            assertEquals("Milk", row.description)
            assertEquals("Two bottles", row.notes)
            assertEquals(1_500L, row.quantityThousandths)
            assertEquals(6_000L, row.ratePaise)
            assertEquals(date, row.transactionDate)
            assertEquals(now, row.createdAt)
            assertEquals(now, row.updatedAt)
        }
    }

    @Test
    fun observeByIdWithCategoryIsNullForAnIdentifierThatIsNotThere() {
        runBlocking {
            assertNull(repository.observeByIdWithCategory(9_999L).first())
            assertNull(repository.getByIdWithCategory(9_999L))
        }
    }

    @Test
    fun getByIdWithCategoryReadsTheRowOnce() {
        runBlocking {
            val id = repository.add(TransactionType.EXPENSE, 500L, groceries.id, date, now = now)

            val row = repository.getByIdWithCategory(id)!!

            assertEquals("Groceries", row.categoryName)
            assertEquals(500L, row.amountPaise)
        }
    }

    @Test
    fun aRowReadBackAfterAnEditShowsTheNewFigures() {
        runBlocking {
            val id = repository.add(TransactionType.EXPENSE, 500L, groceries.id, date, now = now)
            repository.update(
                id = id,
                type = TransactionType.EXPENSE,
                amountPaise = 750L,
                categoryId = groceries.id,
                transactionDate = date,
                now = later,
            )

            val row = repository.observeByIdWithCategory(id).first()!!

            assertEquals(750L, row.amountPaise)
            assertEquals(now, row.createdAt)
            assertEquals(later, row.updatedAt)
        }
    }
}
