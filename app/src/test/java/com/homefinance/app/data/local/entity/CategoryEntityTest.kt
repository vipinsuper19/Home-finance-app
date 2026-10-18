package com.homefinance.app.data.local.entity

import com.homefinance.app.data.model.TransactionType
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The category naming policy is a real rule, not formatting: two names that normalise the same
 * are the same category as far as the unique index is concerned. These tests pin both halves of
 * it - what gets stored, and what is refused outright.
 */
class CategoryEntityTest {

    private val now = Instant.parse("2026-10-16T06:30:00Z")

    @Test
    fun normalizationTrimsTheEnds() {
        assertEquals("Milk", CategoryEntity.normalizeName("  Milk  "))
        assertEquals("Milk", CategoryEntity.normalizeName("\tMilk\n"))
    }

    @Test
    fun normalizationCollapsesRunsOfInnerWhitespace() {
        assertEquals("Milk & Curd", CategoryEntity.normalizeName("Milk   &  Curd"))
        assertEquals("House Rent", CategoryEntity.normalizeName("House \t\n Rent"))
    }

    @Test
    fun normalizationLeavesCapitalisationAlone() {
        assertEquals("Groceries", CategoryEntity.normalizeName("Groceries"))
    }

    @Test
    fun namesThatDifferOnlyByWhitespaceNormalizeToTheSameValue() {
        assertEquals(
            CategoryEntity.normalizeName("Milk"),
            CategoryEntity.normalizeName("  Milk "),
        )
    }

    @Test
    fun blankNamesAreRejected() {
        listOf("", "   ", "\t", "\n  \t ").forEach { blank ->
            assertThrows(IllegalArgumentException::class.java) {
                CategoryEntity.requireValidName(blank)
            }
        }
    }

    @Test
    fun createNormalizesTheNameAndKeepsTheRest() {
        val category = CategoryEntity.create("  House  Rent ", TransactionType.EXPENSE, createdAt = now)

        assertEquals("House Rent", category.name)
        assertEquals(TransactionType.EXPENSE, category.type)
        assertEquals(now, category.createdAt)
        assertTrue(category.isActive)
        assertEquals(0L, category.id)
    }

    @Test
    fun createRejectsABlankName() {
        assertThrows(IllegalArgumentException::class.java) {
            CategoryEntity.create("   ", TransactionType.INCOME, createdAt = now)
        }
    }
}
