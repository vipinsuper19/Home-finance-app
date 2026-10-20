package com.homefinance.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.homefinance.app.HomeFinanceApp
import com.homefinance.app.data.local.entity.CategoryEntity
import com.homefinance.app.data.model.TransactionType
import com.homefinance.app.data.repository.CategoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The two directions, each with its categories, active ones first. */
sealed interface ManageCategoriesUiState {

    data object Loading : ManageCategoriesUiState

    data class Content(
        val income: List<CategoryEntity>,
        val expense: List<CategoryEntity>,
    ) : ManageCategoriesUiState {

        val isEmpty: Boolean get() = income.isEmpty() && expense.isEmpty()
    }

    data class Failed(val cause: Throwable?) : ManageCategoriesUiState
}

/** Which problem the rename dialog is currently stating. */
enum class RenameCategoryError {
    Blank,
    Duplicate,
    Failed,
}

/** The open rename dialog and its input. */
data class RenameCategoryDialogState(
    val categoryId: Long,
    val nameInput: String,
    val isSaving: Boolean = false,
    val error: RenameCategoryError? = null,
)

/**
 * Backs the category management screen.
 *
 * ### Why this is a real screen and not a second creation flow
 *
 * Categories can already be created from a transaction form's picker; this screen deliberately does
 * not repeat that. It does the thing the roadmap named and no screen could do: rename a category,
 * retire one, and bring a retired one back. Every action here is a change to an existing row, so the
 * history filed under it keeps its grouping and the ledger's integrity is never in question.
 *
 * ### Duplicate names are stopped before the database sees them
 *
 * The unique index on `(type, name)` is the authority, but a constraint exception cannot tell the
 * screen *which* row collided. The check here compares case-insensitively against the list already on
 * screen, which is the same rule the index applies, so the dialog can say "another category already
 * uses that name" instead of a generic failure. The index still guards the race.
 */
class ManageCategoriesViewModel(
    private val categories: CategoryRepository,
) : ViewModel() {

    private val reloads = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<ManageCategoriesUiState> = reloads
        .flatMapLatest {
            categories.observeAll()
                .map<List<CategoryEntity>, ManageCategoriesUiState> { all ->
                    ManageCategoriesUiState.Content(
                        income = all.filter { it.type == TransactionType.INCOME },
                        expense = all.filter { it.type == TransactionType.EXPENSE },
                    )
                }
                .onStart { emit(ManageCategoriesUiState.Loading) }
                .catch { failure -> emit(ManageCategoriesUiState.Failed(failure)) }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = ManageCategoriesUiState.Loading,
        )

    private val _renameDialog = MutableStateFlow<RenameCategoryDialogState?>(null)
    val renameDialog: StateFlow<RenameCategoryDialogState?> = _renameDialog.asStateFlow()

    private val _actionFailed = MutableStateFlow(false)
    val actionFailed: StateFlow<Boolean> = _actionFailed.asStateFlow()

    fun retry() {
        reloads.update { it + 1 }
    }

    /** Opens the rename dialog for [category], prefilled with its current name. */
    fun onRenameRequested(category: CategoryEntity) {
        _renameDialog.value = RenameCategoryDialogState(
            categoryId = category.id,
            nameInput = category.name,
        )
    }

    /** Records a keystroke and clears the visible problem, because the input is no longer the old one. */
    fun onNameChange(value: String) {
        _renameDialog.update { it?.copy(nameInput = value, error = null) }
    }

    fun onRenameDismiss() {
        if (_renameDialog.value?.isSaving != true) _renameDialog.value = null
    }

    /**
     * Saves the name, reporting blank and duplicate input in the dialog rather than as a snackbar:
     * both are about the field the household is looking at.
     */
    fun onRenameConfirm() {
        val dialog = _renameDialog.value ?: return
        if (dialog.isSaving) return

        val content = uiState.value as? ManageCategoriesUiState.Content ?: return
        val target = (content.income + content.expense).firstOrNull { it.id == dialog.categoryId } ?: return
        val name = dialog.nameInput.trim()

        if (name.isEmpty()) {
            _renameDialog.value = dialog.copy(error = RenameCategoryError.Blank)
            return
        }

        val collides = (content.income + content.expense).any { other ->
            other.id != target.id &&
                other.type == target.type &&
                other.name.equals(name, ignoreCase = true)
        }
        if (collides) {
            _renameDialog.value = dialog.copy(error = RenameCategoryError.Duplicate)
            return
        }

        _renameDialog.value = dialog.copy(isSaving = true, error = null)

        viewModelScope.launch {
            try {
                categories.rename(target.id, name)
                _renameDialog.value = null
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: IllegalArgumentException) {
                // The repository normalises and re-checks the name, so a blank name can still be
                // refused here if it arrived as whitespace.
                _renameDialog.value = dialog.copy(isSaving = false, error = RenameCategoryError.Blank)
            } catch (_: Exception) {
                _renameDialog.value = dialog.copy(isSaving = false, error = RenameCategoryError.Failed)
            }
        }
    }

    /** Retires or restores [category], reporting a failure rather than leaving the row unchanged in silence. */
    fun setCategoryActive(category: CategoryEntity, active: Boolean) {
        viewModelScope.launch {
            try {
                if (active) categories.activate(category.id) else categories.deactivate(category.id)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                _actionFailed.value = true
            }
        }
    }

    fun onActionFailureShown() {
        _actionFailed.value = false
    }

    companion object {

        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY],
                ) { "ManageCategoriesViewModel needs the Application to reach the repository" }

                ManageCategoriesViewModel((application as HomeFinanceApp).categoryRepository)
            }
        }

        /** The test seam: the same view model over a repository a test supplies. */
        fun factory(categories: CategoryRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { ManageCategoriesViewModel(categories) }
        }
    }
}
