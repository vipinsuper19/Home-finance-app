package com.homefinance.app.core.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Corner radius shared by every rounded surface in Settings, so the screens agree on one shape. */
private val SettingsCardShape = RoundedCornerShape(20.dp)

/** The tinted square a settings row's glyph sits in. */
private val SettingsIconShape = RoundedCornerShape(12.dp)

/**
 * Whether the household's haptic preference currently allows a tick.
 *
 * It is a composition local rather than a parameter on every row because it is a global preference
 * read at the shell: a row that has to be passed a boolean would repeat that parameter through every
 * screen. Defaults to `true`, which matches the stored default.
 */
val LocalHapticsEnabled = staticCompositionLocalOf { true }

/** Provides the haptic preference to everything below it. */
@Composable
fun ProvideHapticsEnabled(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalHapticsEnabled provides enabled, content = content)
}

/**
 * A single, subtle tick for a genuine interaction, or nothing at all when the preference is off.
 *
 * Deliberately *not* fired on composition or on every tap: a settings row calls it when its value
 * actually changes, which is exactly the action the preference describes.
 */
@Composable
fun rememberConfirmationHaptic(): () -> Unit {
    val enabled = LocalHapticsEnabled.current
    val haptics = LocalHapticFeedback.current

    return remember(enabled, haptics) {
        {
            if (enabled) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }
}

/**
 * A settings screen's frame: the shared top bar over a scrollable column.
 *
 * Every Settings destination has the same shape - back arrow, title, and sections of rounded cards -
 * so it is written once here rather than six times with slightly different spacing. The column is
 * scrollable because a household using a large font size must still be able to reach the last row.
 */
@Composable
fun SettingsFrame(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        HomeFinanceTopBar(title = title, onNavigationClick = onBack)
        // The title lives in the top bar only, as it does on every other screen; a subtitle, when a
        // screen has one, sits directly under it so the two read as one heading.
        if (subtitle != null) {
            Text(
                text = subtitle,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            content = content,
        )
    }
}

/** A heading and the rounded card that holds its rows. */
@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        SettingsCard(content = content)
    }
}

/** The rounded surface every group of settings rows is drawn on. */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = SettingsCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(content = content)
    }
}

/** A thin line between two rows of one card. */
@Composable
fun SettingsDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(start = 60.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * A row that opens another screen: glyph, title, current value, chevron.
 *
 * The whole row is the tap target and exposes the title and value as one clickable node, so a screen
 * reader announces "Theme, Light, button" rather than two disconnected pieces of text.
 */
@Composable
fun SettingsNavRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    onClick: () -> Unit,
) {
    val haptic = rememberConfirmationHaptic()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(
                selected = false,
                onClick = {
                    haptic()
                    onClick()
                },
                role = Role.Button,
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsGlyph(icon = icon)

        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A row that holds a switch.
 *
 * The switch itself is not the only target: the whole row toggles, which is the Material guidance and
 * also the only comfortable target for a long title at a large font size. The switch is left
 * non-interactive so a tap cannot be counted twice.
 */
@Composable
fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = rememberConfirmationHaptic()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                onValueChange = {
                    haptic()
                    onCheckedChange(it)
                },
                role = Role.Switch,
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsGlyph(icon = icon)

        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * A row that chooses one value from a short list: radio button, title, explanation.
 *
 * [selected] is drawn with the radio indicator *and* a "Selected" line for screen readers, because
 * colour and a dot alone are not enough at every text size.
 */
@Composable
fun SettingsChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = rememberConfirmationHaptic()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = {
                    haptic()
                    onClick()
                },
                role = Role.RadioButton,
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)

        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A full-width action inside a card, for work rather than navigation. */
@Composable
fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    val haptic = rememberConfirmationHaptic()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(
                selected = false,
                onClick = {
                    haptic()
                    onClick()
                },
                role = Role.Button,
            )
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsGlyph(icon = icon)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A destructive action: a switch row's shape with an error-coloured label. */
@Composable
fun SettingsDestructiveRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = rememberConfirmationHaptic()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(
                selected = false,
                onClick = {
                    haptic()
                    onClick()
                },
                role = Role.Button,
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .size(36.dp)
                .clip(SettingsIconShape)
                .background(MaterialTheme.colorScheme.errorContainer),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
        }

        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A label/value pair inside a card, for facts the screen only reports. */
@Composable
fun SettingsInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** A paragraph inside a card, for the explanation under a group of choices. */
@Composable
fun SettingsNote(
    text: String,
    modifier: Modifier = Modifier,
    emphasised: Boolean = false,
) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        style = MaterialTheme.typography.bodySmall,
        color = if (emphasised) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

/** The small emerald square every settings row's glyph sits in. */
@Composable
private fun SettingsGlyph(icon: ImageVector) {
    Row(
        modifier = Modifier
            .size(36.dp)
            .clip(SettingsIconShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}
