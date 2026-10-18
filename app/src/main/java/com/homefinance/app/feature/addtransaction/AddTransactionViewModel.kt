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
import com.homefinance.app.core.util.QuantityInput
import com.homefinance.app.core.util.QuantityParseResult
import com.homefinance.app.data.local.entity.CategoryEntity
import com.homefinance.app.data.local.relation.TransactionWithCategory
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
 * Backs the form that records a transaction, the form that changes one, and the category picker
 * both of them open.
 *
 * ### One form, two jobs
 *
 * Recording an entry and changing one are the same work from the household's side - same fields,
 * same amount arithmetic, same rules - so they are the same screen and the same view model rather
 * than two of each that could drift apart. What differs is where the form *starts*: a new entry
 * begins from today's date and the type the caller asked for, while an edit begins by reading the
 * entry it is changing. That one read is [EditLoadState], the only phase editing has that adding
 * does not.
 *
 * Passing [editingId] is what makes the difference, and it changes exactly two things: the form
 * fills itself in from that row once, and [save] rewrites that row instead of inserting a new one.
 * It never inserts a replacement: if the entry has been deleted, the save reports that instead.
 *
 * ### Why one state for two screens
 *
 * Picking a category is part of filling in the form, not a separate piece of work with its own life:
 * the picker lists the categories of the type the form is currently on, and choosing one has to
 * change the form. Two view models would mean one of them handing a result back through navigation
 * arguments and a saved-state handle, where a missed result shows a form that silently forgot what
 * was just chosen. One state makes the choice and the form's memory of it the same fact, and the
 * picker's list is then guaranteed to be the list the form's selection came from.
 *
 * ### Where the data comes from
 *
 * The category list comes from [CategoryRepository.observeByType], re-read every time the type is
 * flipped, and filtered to the **active** categories of that type. Nothing here polls: Room pushes a
 * new list whenever a category changes, so a category created in the picker's dialog appears without
 * this class asking for it again.
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
 * This class validates only what the *screen* needs to complain about: a parseable, positive amount
 * (typed, or derived from a quantity and a rate), and a category having been chosen. The
 * authoritative rules - the amount really is positive, the category really exists and really matches
 * the type, and the amount really is `quantity x rate` - stay in [TransactionRepository], which
 * refuses any write that breaks them. Duplicating those checks here would create a second copy of the
 * rules that could drift from the tested one. The derivation itself is not duplicated even in spirit:
 * the preview and the repository both call [QuantityInput.amountPaise].
 *
 * ### Saving
 *
 * [save] does not report success on its own authority. It awaits the repository and only then
 * records the transaction's id in [AddTransactionUiState.savedTransactionId]. Until that id exists
 * the button stays disabled and [save] refuses, so a double tap cannot write the same entry twice -
 * including across a configuration change, because the flag lives in the view model, not the
 * composable.
 *
 * @param initialType the type a *new* entry starts on. Ignored when [editingId] is given, whose own
 *   type replaces it as soon as the row has been read - the form is not drawn before then, so the
 *   placeholder is never seen.
 * @param editingId the transaction this form changes, or `null` to record a new one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddTransactionViewModel(
    private val transactions: TransactionRepository,
    private val categories: CategoryRepository,
    initialType: TransactionType,
    private val editingId: Long? = null,
    today: LocalDate = LocalDate.now(),
) : ViewModel() {

    private val state = MutableStateFlow(
        AddTransactionUiState(
            type = initialType,
            date = today,
            // An edit has to read its row before it can draw itself; a new entry is ready at once.
            editLoad = if (editingId == null) EditLoadState.Ready else EditLoadState.Loading,
        ),
    )

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

        if (editingId != null) loadForEdit()
    }

    /**
     * Reads the entry being changed, once, and fills the form in from it.
     *
     * Once, deliberately: the household is about to type into these fields, and a live read would
     * let the database overwrite the half-written edit under their hands.
     *
     * The read resolves the category's *name* as well as its id, so a row filed under a category
     * that has since been retired still shows the name it was filed under. The name is not
     * invented here - it is the one the join returned.
     */
    private fun loadForEdit() {
        val id = editingId ?: return

        viewModelScope.launch {
            state.update { it.copy(editLoad = EditLoadState.Loading) }

            val row = try {
                transactions.getByIdWithCategory(id)
            } catch (failure: Exception) {
                state.update { it.copy(editLoad = EditLoadState.Failed(failure)) }
                return@launch
            }

            state.update { current ->
                if (row == null) {
                    current.copy(editLoad = EditLoadState.NotFound)
                } else {
                    current.prefilledFrom(row)
                }
            }
        }
    }

    /** Asks for the entry again after a failed read. Does nothing on a form that records a new one. */
    fun retryEditLoad() {
        if (editingId != null) loadForEdit()
    }

    /** Flips the form between income and expense. A category of the other type cannot be kept. */
    fun onTypeChange(type: TransactionType) {
        state.update { current ->
            if (current.type == type) {
                current
            } else {
                current.copy(
                    type = type,
                    category = null,
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

    /**
     * Records the chosen category, named as the offered list names it.
     *
     * An id that is not among the offered categories is ignored rather than stored: a category can
     * only be chosen from the list the picker showed, and keeping an id this form cannot name would
     * put "nothing chosen" on screen while the save used something else.
     */
    fun onCategorySelected(categoryId: Long) {
        state.update { current ->
            val option = (current.categories as? CategoriesUiState.Ready)
                ?.options
                ?.firstOrNull { it.id == categoryId }
                ?: return@update current

            current.copy(category = option, showCategoryError = false)
        }
    }

    fun onDateChange(date: LocalDate) {
        state.update { it.copy(date = date) }
    }

    fun onDescriptionChange(value: String) {
        state.update { it.copy(description = value) }
    }

    fun onNotesChange(value: String) {
        state.update { it.copy(notes = value) }
    }

    /**
     * Switches the entry between a flat amount and a quantity bought at a rate.
     *
     * Both what was typed and every complaint about it are kept or dropped together: the amount field
     * stops deciding the amount while the switch is on, so a sentence about *it* would be about a
     * control the household can no longer see. The typed figures are deliberately *not* discarded, so
     * flipping the switch back finds the entry as it was left.
     */
    fun onQuantityAndRateToggled(enabled: Boolean) {
        state.update { current ->
            if (current.useQuantityAndRate == enabled) {
                current
            } else {
                current.copy(
                    useQuantityAndRate = enabled,
                    amountError = null,
                    quantityError = null,
                    rateError = null,
                )
            }
        }
    }

    fun onQuantityChange(value: String) {
        state.update { it.copy(quantityInput = value, quantityError = null) }
    }

    fun onRateChange(value: String) {
        state.update { it.copy(rateInput = value, rateError = null) }
    }

    /** Asks for the category list again after a failed read. */
    fun retryCategories() {
        categoryReloads.update { it + 1 }
    }

    /**
     * Validates the form and, if it passes, records or rewrites the transaction.
     *
     * Which amount is checked depends on the mode: the typed one, or the exact product of the quantity
     * and the rate. A product that is not positive is refused here rather than left to the repository,
     * because the repository's refusal of a *negative* amount is a bug report about the code, while
     * "0.001 at 1 paise is not enough to record" is something the household can act on.
     *
     * See the class documentation for why a successful return here is *not* the same as a
     * successful save: only [AddTransactionUiState.savedTransactionId] becoming non-null means the
     * row exists.
     */
    fun save() {
        val current = state.value
        // Already writing, or already written and the screen has not left yet: a second tap must
        // not produce a second write.
        if (current.isSaving || current.savedTransactionId != null) return

        val amountPaise: Long
        val quantityThousandths: Long?
        val ratePaise: Long?

        if (current.useQuantityAndRate) {
            val quantity = QuantityInput.parseToThousandths(current.quantityInput)
            if (quantity is QuantityParseResult.Problem) {
                state.update { it.copy(quantityError = quantity, saveError = null) }
                return
            }
            val rate = MoneyInput.parseRupeesToPaise(current.rateInput)
            if (rate is MoneyParseResult.Problem) {
                state.update { it.copy(rateError = rate, saveError = null) }
                return
            }

            quantityThousandths = (quantity as QuantityParseResult.Success).thousandths
            ratePaise = (rate as MoneyParseResult.Success).paise

            val derived = QuantityInput.amountPaise(quantityThousandths, ratePaise)
            if (derived == null) {
                state.update {
                    it.copy(amountError = MoneyParseResult.Problem.TooLarge, saveError = null)
                }
                return
            }
            if (derived <= 0L) {
                state.update {
                    it.copy(amountError = MoneyParseResult.Problem.NotPositive, saveError = null)
                }
                return
            }
            amountPaise = derived
        } else {
            val parsed = MoneyInput.parseRupeesToPaise(current.amountInput)
            if (parsed is MoneyParseResult.Problem) {
                state.update { it.copy(amountError = parsed, saveError = null) }
                return
            }

            amountPaise = (parsed as MoneyParseResult.Success).paise
            quantityThousandths = null
            ratePaise = null
        }

        val category = current.category
        if (category == null) {
            state.update { it.copy(showCategoryError = true, saveError = null) }
            return
        }

        state.update {
            it.copy(
                isSaving = true,
                amountError = null,
                quantityError = null,
                rateError = null,
                showCategoryError = false,
                saveError = null,
            )
        }

        val editing = editingId
        viewModelScope.launch {
            try {
                if (editing == null) {
                    val id = transactions.add(
                        type = current.type,
                        amountPaise = amountPaise,
                        categoryId = category.id,
                        transactionDate = current.date,
                        description = current.description,
                        notes = current.notes,
                        quantityThousandths = quantityThousandths,
                        ratePaise = ratePaise,
                    )
                    state.update { it.copy(isSaving = false, savedTransactionId = id) }
                } else {
                    // Not `add`, ever: an edit that inserted would leave the household with two
                    // entries and a "changed" one that was not the one they changed.
                    val rewritten = transactions.update(
                        id = editing,
                        type = current.type,
                        amountPaise = amountPaise,
                        categoryId = category.id,
                        transactionDate = current.date,
                        description = current.description,
                        notes = current.notes,
                        quantityThousandths = quantityThousandths,
                        ratePaise = ratePaise,
                    )
                    // The same id comes back as the "saved" one, so the screen leaves on success
                    // exactly as it does after an insert.
                    state.update {
                        if (rewritten) {
                            it.copy(isSaving = false, savedTransactionId = editing)
                        } else {
                            it.copy(isSaving = false, saveError = SaveError.TransactionMissing)
                        }
                    }
                }
            } catch (refused: IllegalArgumentException) {
                // The repository refused the write as impossible. From this screen that can only mean
                // the category - the picker offers only the form's type and categories are never
                // deleted - because the amount was derived here with the same function the repository
                // re-checks it with, so it cannot disagree with the quantity and the rate beside it.
                state.update { it.copy(isSaving = false, saveError = SaveError.CategoryUnavailable) }
            } catch (failure: Exception) {
                state.update { it.copy(isSaving = false, saveError = SaveError.Failed(failure)) }
            }
        }
    }

    /** Opens the "new category" dialog, which only the category picker offers. */
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
     * Creates the category named in the dialog and selects it for the entry being written.
     *
     * The name stored on the selection is the *normalised* one, produced by the same
     * [CategoryEntity.normalizeName] the repository used to write the row, so the form shows exactly
     * what the database holds rather than what was typed - and it shows it immediately, without
     * waiting for the category list to come back round.
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
                        category = CategoryOption(id = id, name = CategoryEntity.normalizeName(name)),
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
                        .map { CategoryOption(id = it.id, name = it.name, iconKey = it.iconKey) },
                )
            }
            .onStart { emit(CategoriesUiState.Loading) }
            .catch { failure -> emit(CategoriesUiState.Failed(failure)) }

    /** Records a dialog failure only while the dialog is still open. */
    private fun AddTransactionUiState.withDialogError(error: CategoryDialogError) =
        copy(categoryDialog = categoryDialog?.copy(isSaving = false, error = error))

    /**
     * Fills the form in from the row being changed.
     *
     * Every figure is written back as the household would have *typed* it - [MoneyInput.toEditableText]
     * and [QuantityInput.toEditableText], not the display formatters - because these strings go into
     * input fields the same parsers will read again. A row saved as `₹90.00` fills the amount field
     * with `90`, and saving it unchanged stores 9000 paise again.
     *
     * The category is taken as a whole [CategoryOption] from the row's own id and the name the join
     * returned, rather than by looking the id up in the offered list: an entry filed under a category
     * that has since been retired must still show the name it was filed under. Re-selecting it from
     * the picker is impossible, which is the point of retiring a category - it is not offered again -
     * but the entry keeps its own.
     *
     * `useQuantityAndRate` follows the row's own shape rather than the form's last state, and the
     * amount is filled in either way, so turning the switch off on a quantity-and-rate entry leaves
     * the figure it was worth to be edited as a plain amount.
     */
    private fun AddTransactionUiState.prefilledFrom(row: TransactionWithCategory): AddTransactionUiState {
        val quantityThousandths = row.quantityThousandths
        val ratePaise = row.ratePaise

        return copy(
            type = row.type,
            date = row.transactionDate,
            amountInput = MoneyInput.toEditableText(row.amountPaise),
            amountError = null,
            category = CategoryOption(
                id = row.categoryId,
                name = row.categoryName,
                iconKey = row.categoryIconKey,
            ),
            showCategoryError = false,
            description = row.description.orEmpty(),
            notes = row.notes.orEmpty(),
            useQuantityAndRate = quantityThousandths != null && ratePaise != null,
            quantityInput = quantityThousandths?.let(QuantityInput::toEditableText).orEmpty(),
            quantityError = null,
            rateInput = ratePaise?.let(MoneyInput::toEditableText).orEmpty(),
            rateError = null,
            editLoad = EditLoadState.Ready,
        )
    }

    companion object {

        /**
         * Builds the view model for a form opened with [initialType], changing the transaction with
         * [editingId] when one is given.
         *
         * Same one wiring seam as the rest of the app: the application class owns the single
         * database and its repositories, and there is deliberately no dependency-injection
         * framework. [initialType] and [editingId] are real arguments because they are chosen by
         * the caller rather than by the view model's constructor signature.
         */
        fun factory(
            initialType: TransactionType,
            editingId: Long? = null,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY],
                ) { "AddTransactionViewModel needs the Application to reach the repositories" }

                AddTransactionViewModel(
                    transactions = (application as HomeFinanceApp).transactionRepository,
                    categories = application.categoryRepository,
                    initialType = initialType,
                    editingId = editingId,
                )
            }
        }

        /**
         * Builds the view model over [transactions] and [categories] directly.
         *
         * The same seam as `HomeFinanceDatabase.build(context, name)` exists for: a test that drives
         * the real navigation graph - and therefore the shared, graph-scoped view model - must not
         * reach the household's own database through the application class. Production always takes
         * [factory], and the two differ only in where the repositories come from.
         */
        fun factory(
            initialType: TransactionType,
            transactions: TransactionRepository,
            categories: CategoryRepository,
            editingId: Long? = null,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AddTransactionViewModel(
                    transactions = transactions,
                    categories = categories,
                    initialType = initialType,
                    editingId = editingId,
                )
            }
        }
    }
}
