package dev.kitchenapp.sharedui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

internal enum class AppContentStateKind {
    Loading,
    Empty,
    Error,
    Review,
    Success,
}

internal data class AppContentState(
    val kind: AppContentStateKind,
    val title: String,
    val message: String,
    val actionLabel: String? = null,
)

@Composable
internal fun AppContentStatePanel(
    state: AppContentState,
    modifier: Modifier = Modifier,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (state.kind == AppContentStateKind.Loading) {
            CircularProgressIndicator()
        }

        val stateLabel = when (state.kind) {
            AppContentStateKind.Loading,
            AppContentStateKind.Empty -> null
            AppContentStateKind.Error -> "Error"
            AppContentStateKind.Review -> "Review needed"
            AppContentStateKind.Success -> "Success"
        }
        if (stateLabel != null) {
            Text(
                modifier = Modifier.padding(bottom = 8.dp),
                text = stateLabel,
                color = if (state.kind == AppContentStateKind.Error) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                style = MaterialTheme.typography.labelLarge,
            )
        }

        Text(
            text = state.title,
            color = if (state.kind == AppContentStateKind.Error) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .padding(top = 8.dp),
            text = state.message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )

        if (state.actionLabel != null && onAction != null) {
            Button(
                modifier = Modifier.padding(top = 16.dp),
                onClick = onAction,
            ) {
                Text(state.actionLabel)
            }
        }
    }
}
