package com.homefinance.app.feature.addtransaction

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homefinance.app.R
import com.homefinance.app.core.ui.component.HomeFinanceTopBar
import com.homefinance.app.data.model.TransactionType

/**
 * The form that changes an existing transaction - the edit half of the form whose add half is
 * [AddTransactionScreen].
 *
 * ### One screen, not two
 *
 * It draws [AddTransactionContent], the very same body the add screen draws, so the fields, their
 * order, their wording and every rule about them are shared rather than copied: a change to the
 * amount field is a change to both screens in one edit. Only three things differ, and all three are
 * words - the title, the button, and what the button says while it is working.
 *
 * ### Where the figures come from
 *
 * The view model reads the row once, by [transactionId], and fills the form in from it. This screen
 * therefore has three states the add screen does not: still reading, nothing to read (the entry has
 * been deleted), and the read failed with a retry offered. The form is not drawn in any of them,
 * which is what stops a half-filled form being saved over a transaction it never read.
 *
 * ### Leaving the screen
 *
 * [onFinished] is called only once the update has landed, and the screen it returns to is the details
 * screen the household came from - which re-reads the row, so it is showing the changed figures
 * rather than the ones it was opened with. Backing out with [onBack] writes nothing: there is no
 * draft to discard, because nothing is written until Save is tapped.
 *
 * @param transactionId the id of the transaction being changed. It is passed in rather than read from
 *   the view model so the screen can be rendered directly in a test; production gives it the
 *   argument the navigation route carried.
 */
@Composable
fun EditTransactionScreen(
    transactionId: Long,
    onBack: () -> Unit,
    onFinished: () -> Unit,
    onPickCategory: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddTransactionViewModel = viewModel(
        factory = AddTransactionViewModel.factory(
            initialType = TransactionType.EXPENSE,
            editingId = transactionId,
        ),
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.savedTransactionId) {
        if (uiState.savedTransactionId != null) onFinished()
    }

    when (val load = uiState.editLoad) {
        EditLoadState.Loading -> EditPane(onBack = onBack, modifier = modifier) {
            CircularProgressIndicator()
        }

        EditLoadState.NotFound -> EditPane(onBack = onBack, modifier = modifier) {
            EditNotice(
                icon = Icons.AutoMirrored.Outlined.List,
                title = stringResource(R.string.edit_transaction_not_found_title),
                message = stringResource(R.string.edit_transaction_not_found_message),
                actionLabel = stringResource(R.string.edit_transaction_not_found_action),
                onAction = onBack,
            )
        }

        is EditLoadState.Failed -> EditPane(onBack = onBack, modifier = modifier) {
            EditNotice(
                icon = Icons.Outlined.Warning,
                title = stringResource(R.string.edit_transaction_error_title),
                message = stringResource(R.string.edit_transaction_error_message),
                accentColor = MaterialTheme.colorScheme.errorContainer,
                accentContentColor = MaterialTheme.colorScheme.onErrorContainer,
                actionLabel = stringResource(R.string.edit_transaction_retry),
                onAction = viewModel::retryEditLoad,
            )
        }

        EditLoadState.Ready -> AddTransactionContent(
            uiState = uiState,
            title = stringResource(R.string.edit_transaction_title),
            actionLabel = stringResource(R.string.edit_transaction_save),
            progressLabel = stringResource(R.string.edit_transaction_saving),
            onBack = onBack,
            onTypeChange = viewModel::onTypeChange,
            onAmountChange = viewModel::onAmountChange,
            onPickCategory = onPickCategory,
            onDateChange = viewModel::onDateChange,
            onDescriptionChange = viewModel::onDescriptionChange,
            onNotesChange = viewModel::onNotesChange,
            onQuantityAndRateToggled = viewModel::onQuantityAndRateToggled,
            onQuantityChange = viewModel::onQuantityChange,
            onRateChange = viewModel::onRateChange,
            onSave = viewModel::save,
            modifier = modifier,
        )
    }
}

/**
 * The screen's title bar with one thing centred under it.
 *
 * The bar is drawn in every phase, so the household can always leave - including from the state that
 * says the entry they asked to change is no longer there.
 */
@Composable
private fun EditPane(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        HomeFinanceTopBar(
            title = stringResource(R.string.edit_transaction_title),
            onNavigationClick = onBack,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

/**
 * A centred glyph, headline, supporting sentence and one action, for the phases with no form in them.
 *
 * It mirrors the ledger's status message and the picker's, deliberately: a read that found nothing
 * and a read that failed should look like the same kind of thing wherever they happen, and only the
 * accent colour separates "there is nothing here" from "this did not work".
 */
@Composable
private fun EditNotice(
    icon: ImageVector,
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    accentContentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = accentColor,
            contentColor = accentContentColor,
        ) {
            Box(
                modifier = Modifier.size(72.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = icon, contentDescription = null)
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onAction) {
            Text(text = actionLabel)
        }
    }
}
