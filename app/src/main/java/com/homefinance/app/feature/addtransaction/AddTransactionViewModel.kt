package com.homefinance.app.feature.addtransaction

import android.database.sqlite.SQLiteConstraintException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.homefinance.app.HomeFinanceApp
import com.homefinance.app.core.util.MoneyInput
import com.homefinance.app.core.util.MoneyParseResult
import com.homefinance.app.data.local.entity.CategoryEntity
import com.homefinance.app.data.model.TransactionType
import com.homefinance.app.data.repository.CategoryRepository
import com.homefinance.app.data.repository.TransactionRepository
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs the add-income / add-expense form.
 *
 * ### Where the data comes from
 *
 * The category chips come from [CategoryRepository.observeByType], re-read every time the type is
 * flipped, and filtered to the **active** categories of that type. Nothing here polls: Room pushes
 * a new list whenever a category changes, so a category created in the dialog appears without this
 * class asking for it again.
 *
 * ### Why the form is one [MutableStateFlow] with a collector feeding it
 *
 * The state is one flow rather than a chain of `combine`s because the form and the category list
 * are genuinely one screen's state but change for different reasons. A single collector watches
 * the *type* only - `distinctUntilChanged` on the type is what stops the write-back of the
 * category list from re-triggering the collector, so there is no feedback loop - and writes the
 * options into the same state. [retryCategories] nudges a counter to restart that read after a
 * failure, exactly as [com.homefinance.app.feature.transactions.TransactionsViewModel] does.
 *
 * ### Where the rules live
 *
 * This class validates only what the *screen* needs to complain about: a parseable, positive
 * amount, and a category having been chosen. The authoritative rules - the amount really is
 * positive, the category really exists and really matches the type - stay in
 * [TransactionRepository], which refuses any write that breaks them. Duplicating those checks here
 * would create a second copy of the rules that could drift from the tested one.
 *
 * ### Saving
 *
 * [save] does not report success on its own authority. It awaits the repository and only then
 * records the new id in [AddTransactionUiState.savedTransactionId]. Until that id exists the
 * button stays disabled and [save] refuses, so a double tap cannot write the same entry twice -
 * including across a configuration change, because the flag lives in the view model, not the
 * composable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddTransactionViewModel(
    private val transactions: TransactionRepository,
    private val categories: CategoryRepository,
    initialType: TransactionType,
    today: LocalDate = LocalDate.now(),
) : ViewModel() {

    private val state = MutableStateFlow(AddTransactionUiState(type = initialType, date = today))

    /** The form exactly as the screen should render it. */
    val uiState: StateFlow<AddTransactionUiState> = state.asStateFlow()

    private val categoryReloads = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            combine(
                state.map { it.type }.distinctUntilChanged(),
                categoryReloads,
            ) { type, _ -> type }
                .flatMapLatest { observeActiveCategories(it) }
                .collect { options -> state.update { it.copy(categories = options) } }
        }
    }

    /** Flips the form between income and expense. A category of the other type cannot be kept. */
    fun onTypeChange(type: TransactionType) {
        state.update { current ->
            if (current.type == type) {
                current
            } else {
                current.copy(
                    type = type,
                    categoryId = null,
                    showCategoryError = false,
                    saveError = null,
                )
            }
        }
    }

    /** Records what the household typed. Clears the previous complaint about it. */
    fun onAmountChange(value: String) {
        state.update { it.copy(amountInput = value, amountError = null) }
    }

    fun onCategorySelected(categoryId: Long) {
        state.update { it.copy(categoryId = categoryId, showCategoryError = false) }
    }

    fun onDateChange(date: LocalDate) {
        state.update { it.copy(date = date) }
    }

    fun onDescriptionChange(value: String) {
        state.update { it.copy(description = value) }
    }

    /** Asks for the category list again after a failed read. */
    fun retryCategories() {
        categoryReloads.update { it + 1 }
    }

    /**
     * Validates the form and, if it passes, records the transaction.
     *
     * See the class documentation for why a successful return here is *not* the same as a
     * successful save: only [AddTransactionUiState.savedTransactionId] becoming non-null means the
     * row exists.
     */
    fun save() {
        val current = state.value
        // Already writing, or already written and the screen has not left yet: a second tap must
        // not produce a second row.
        if (current.isSaving || current.savedTransactionId != null) return

        val parsed = MoneyInput.parseRupeesToPaise(current.amountInput)
        if (parsed is MoneyParseResult.Problem) {
            state.update { it.copy(amountError = parsed, saveError = null) }
            return
        }
        val amountPaise = (parsed as MoneyParseResult.Success).paise

        val categoryId = current.categoryId
        if (categoryId == null) {
            state.update { it.copy(showCategoryError = true, saveError = null) }
            return
        }

        state.update {
            it.copy(
                isSaving = true,
                amountError = null,
                showCategoryError = false,
                saveError = null,
            )
        }

        viewModelScope.launch {
            try {
                val id = transactions.add(
                    type = current.type,
                    amountPaise = amountPaise,
                    categoryId = categoryId,
                    transactionDate = current.date,
                    description = current.description,
                )
                state.update { it.copy(isSaving = false, savedTransactionId = id) }
            } catch (refused: IllegalArgumentException) {
                // The repository refused the write: the category is gone or is the wrong type.
                state.update { it.copy(isSaving = false, saveError = SaveError.CategoryUnavailable) }
            } catch (failure: Exception) {
                state.update { it.copy(isSaving = false, saveError = SaveError.Failed(failure)) }
            }
        }
    }

    /** Opens the "new category" dialog, used only when the selected type has nothing to pick. */
    fun openCategoryDialog() {
        state.update { it.copy(categoryDialog = CategoryDialogState()) }
    }

    fun dismissCategoryDialog() {
        state.update { it.copy(categoryDialog = null) }
    }

    fun onCategoryNameChange(value: String) {
        state.update { current ->
            val dialog = current.categoryDialog ?: return@update current
            current.copy(categoryDialog = dialog.copy(nameInput = value, error = null))
        }
    }

    /**
     * Creates the category named in the dialog and selects it.
     *
     * A duplicate surfaces as [SQLiteConstraintException] from the unique index on
     * `(type, name)` rather than from a "does this exist?" query here - see
     * [CategoryRepository.create] for why the index is the only race-free check. A blank name is
     * refused before the repository is called, so a household tapping Create on an empty field
     * gets a sentence rather than an exception.
     */
    fun createCategory() {
        val current = state.value
        val dialog = current.categoryDialog ?: return
        if (dialog.isSaving) return

        if (dialog.nameInput.isBlank()) {
            state.update {
                it.copy(categoryDialog = dialog.copy(error = CategoryDialogError.BlankName))
            }
            return
        }

        state.update {
            it.copy(categoryDialog = dialog.copy(isSaving = true, error = null))
        }

        val type = current.type
        val name = dialog.nameInput
        viewModelScope.launch {
            try {
                val id = categories.create(name = name, type = type)
                state.update {
                    it.copy(
                        categoryId = id,
                        showCategoryError = false,
                        categoryDialog = null,
                    )
                }
            } catch (duplicate: SQLiteConstraintException) {
                state.update { it.withDialogError(CategoryDialogError.Duplicate) }
            } catch (blank: IllegalArgumentException) {
                // Defence in depth: the blank check above should have caught this first.
                state.update { it.withDialogError(CategoryDialogError.BlankName) }
            } catch (failure: Exception) {
                state.update { it.withDialogError(CategoryDialogError.Failed(failure)) }
            }
        }
    }

    /**
     * The active categories of [type], newest read first and already retired-free.
     *
     * `catch` ends the flow on failure, so a broken read becomes a message with a retry rather
     * than a spinner that never stops; [retryCategories] restarts it.
     */
    private fun observeActiveCategories(type: TransactionType): Flow<CategoriesUiState> =
        categories.observeByType(type)
            .map<List<CategoryEntity>, CategoriesUiState> { rows ->
                CategoriesUiState.Ready(
                    rows.filter { it.isActive }
                        .map { CategoryOption(id = it.id, name = it.name) },
                )
            }
            .onStart { emit(CategoriesUiState.Loading) }
            .catch { failure -> emit(CategoriesUiState.Failed(failure)) }

    /** Records a dialog failure only while the dialog is still open. */
    private fun AddTransactionUiState.withDialogError(error: CategoryDialogError) =
        copy(categoryDialog = categoryDialog?.copy(isSaving = false, error = error))

    companion object {

        /**
         * Builds the view model for a form opened with [initialType].
         *
         * Same one wiring seam as the rest of the app: the application class owns the single
         * database and its repositories, and there is deliberately no dependency-injection
         * framework. [initialType] is a real argument because it is chosen by the caller rather
         * than by the view model's constructor signature.
         */
        fun factory(initialType: TransactionType): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY],
                ) { "AddTransactionViewModel needs the Application to reach the repositories" }

                AddTransactionViewModel(
                    transactions = (application as HomeFinanceApp).transactionRepository,
                    categories = application.categoryRepository,
                    initialType = initialType,
                )
            }
        }
    }
}
