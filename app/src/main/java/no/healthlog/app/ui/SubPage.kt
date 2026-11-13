package no.healthlog.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import no.healthlog.app.R

/**
 * Felles ramme for undersider (Vei deg, Kroppssammensetning ...): fullskjerm med tittel
 * og tilbakepil øverst, slik at alle undersider ser like ut og systemets tilbake-knapp
 * lukker siden.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubPage(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = actions,
                    // Dialogvinduet håndterer systemfeltene selv.
                    windowInsets = WindowInsets(0, 0, 0, 0)
                )
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            content = content
        )
    }
}

/**
 * Underside som vises INNE i fanen (bunnmenyen og meldinger nederst forblir synlige),
 * f.eks. delene av Mer. Systemets tilbake-knapp går tilbake til [onBack].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InPlacePage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
            // Scaffold-en rundt har allerede lagt inn plass til statusfeltet.
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
        )
        content()
    }
}

/**
 * Spørsmålet som vises når du går tilbake fra et skjema med endringer som ikke er lagret:
 * Lagre, Forkast eller bli værende (Avbryt). [canSave] false = skjemaet har feil, så bare
 * Forkast og Avbryt er mulig.
 */
@Composable
fun UnsavedChangesDialog(canSave: Boolean, onSave: () -> Unit, onDiscard: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.unsaved_title)) },
        text = { Text(stringResource(if (canSave) R.string.unsaved_body else R.string.unsaved_body_invalid)) },
        confirmButton = {
            if (canSave) TextButton(onClick = onSave) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                TextButton(onClick = onDiscard) {
                    Text(stringResource(R.string.unsaved_discard), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    )
}
