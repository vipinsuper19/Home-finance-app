package com.homefinance.app.core.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A centred glyph, a headline and an optional sentence, for the states that have no rows to show.
 *
 * ### Why this is shared
 *
 * Loading, empty, failed and "nothing here yet" are the same *kind* of thing wherever they happen, and
 * a household should not have to learn a second visual vocabulary for them when they move between
 * tabs. Before this existed the same twenty lines were written again in each feature, which is how a
 * failure ends up dressed in the reassuring green an empty list uses.
 *
 * The accent colour is a parameter precisely because those states are *not* interchangeable: a failed
 * read passes the error container colours so it cannot be mistaken for a calm empty state, and a
 * genuinely empty list passes the neutral ones. The colour is never the only cue - the headline says
 * what happened, and [action] is a real button rather than a coloured area.
 *
 * @param icon the decorative glyph; `null` content description, because [title] already says it.
 * @param message an optional second line, for states that want a sentence about what to do next.
 * @param action an optional control beneath - a Retry, or the button that starts the first entry.
 */
@Composable
fun StatusPane(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    accentColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    accentContentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    action: (@Composable () -> Unit)? = null,
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
                modifier = Modifier.size(64.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = icon, contentDescription = null)
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (message != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(modifier = Modifier.height(16.dp))
            action()
        }
    }
}
